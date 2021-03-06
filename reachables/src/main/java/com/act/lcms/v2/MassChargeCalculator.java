/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package com.act.lcms.v2;

import com.act.lcms.MS1;
import com.act.lcms.MassCalculator;
import com.act.lcms.db.io.DB;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class MassChargeCalculator {
  private static final Logger LOGGER = LogManager.getFormatterLogger(MassChargeCalculator.class);

  /**
   * An MZSource is a handle to the result of ionic mass/charge computation.  Once m/z computation has been completed
   * for a given set of mass sources (like InChIs, arbitrary numeric values, and the results of the very convenient
   * Utils.extractMassFromString), the resulting MZSource object can be used to access all of the m/z values
   * computed from that source of a monoisotopic mass.  In turn, any peaks associated with a particular m/z can be
   * mapped back to the source from which the search window in which the peak was found was originally derived.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL) // Hide unused fields since this is effectively a tagged union/sum type.
  public static class MZSource implements Serializable {
    private static final long serialVersionUID = -6359715907934400901L;

    @JsonIgnore
    private static transient AtomicInteger instanceCounter = new AtomicInteger(0);

    private enum KIND {
      INCHI, // An InChI whose mass to import.
      ARBITRARY_MASS, // Some monoisotopic mass specified without a source molecule.
      PRE_EXTRACTED_MASS, // The output of Utils.extractMassFromString, which is very handy.
      UNKNOWN, // For deserialization.
      ;
    }

    @JsonProperty(value = "kind", required = true)
    KIND kind = KIND.UNKNOWN;
    @JsonProperty(value = "inchi")
    String inchi;
    @JsonProperty(value = "arbitrary_mass")
    Double arbitraryMass;
    // Don't use Pair here, as Jackson 2.6 chokes on it.
    @JsonProperty(value = "pre_extracted_label")
    String preExtractedLabel;
    @JsonProperty(value = "pre_extracted_mass")
    Double preExtractedMass;
    /* Note: this ID is for logging/convenience only.  It is not intended as a durable or consistent pointer to a
     * particular MZSource.  The caller is responsible for understanding the true source/meaning of an MZSource
     * object; by itself, it is just a handle to some computation. */
    @JsonProperty(value = "id", required = true)
    @JsonDeserialize(using = MZSourceIDDeserializer.class)
    Integer objectId;

    /**
     * Construct a source based on an InChI.  Monoisotopic mass will automatically be calculated using MassCalculator.
     * @param inchi The inchi whose mass to use.
     */
    public MZSource(String inchi) {
      this.kind = KIND.INCHI;
      this.inchi = inchi;
      setId();
    }

    /**
     * Construct a source based on an arbitrary monoisotopic mass.  Do not use average mass for this value.
     * @param arbitraryMass Some monoisotopic mass to use as a source.
     */
    public MZSource(Double arbitraryMass) {
      this.kind = KIND.ARBITRARY_MASS;
      this.arbitraryMass = arbitraryMass;
      setId();
    }

    /**
     * Construct a source based on the result of {@link com.act.lcms.db.analysis.Utils#extractMassFromString}.
     * @param extractMassFromStringResult The results of {@link com.act.lcms.db.analysis.Utils#extractMassFromString}.
     */
    public MZSource(Pair<String, Double> extractMassFromStringResult) {
      this.kind = KIND.PRE_EXTRACTED_MASS;
      this.preExtractedLabel = extractMassFromStringResult.getLeft();
      this.preExtractedMass  = extractMassFromStringResult.getRight();
      setId();
    }

    private MZSource() { // For de/serialization.

    }

    private void setId() {
      objectId = instanceCounter.getAndIncrement();
    }

    Integer getId() {
      return objectId;
    }

    // No protection specification here = package private.
    KIND getKind() {
      return kind;
    }

    String getInchi() {
      return inchi;
    }

    Double getArbitraryMass() {
      return arbitraryMass;
    }

    Pair<String, Double> getPreExtractedMass() {
      return Pair.of(preExtractedLabel, preExtractedMass);
    }

    /* Note: these methods are more complicated than they need be.  Specifically, since objectId should be unique, it's
     * really the only thing we should need to determine if two MZSource objects are equivalent.  However, given the
     * complexities of serialization, testing, etc., comprehensive implementations of these methods seem like a good
     * idea for now.
     */
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      MZSource mzSource = (MZSource) o;

      if (kind != mzSource.kind) return false;
      if (inchi != null ? !inchi.equals(mzSource.inchi) : mzSource.inchi != null) return false;
      if (arbitraryMass != null ? !arbitraryMass.equals(mzSource.arbitraryMass) : mzSource.arbitraryMass != null)
        return false;
      if (preExtractedMass != null ? !preExtractedMass.equals(mzSource.preExtractedMass) : mzSource.preExtractedMass != null)
        return false;
      return objectId.equals(mzSource.objectId);

    }

    @Override
    public int hashCode() {
      int result = kind.hashCode();
      result = 31 * result + (inchi != null ? inchi.hashCode() : 0);
      result = 31 * result + (arbitraryMass != null ? arbitraryMass.hashCode() : 0);
      result = 31 * result + (preExtractedMass != null ? preExtractedMass.hashCode() : 0);
      result = 31 * result + objectId.hashCode();
      return result;
    }

    public static class MZSourceIDDeserializer extends JsonDeserializer<Integer> {
      @Override
      public Integer deserialize(JsonParser p, DeserializationContext ctxt)
          throws IOException, JsonProcessingException {
        final int thisId = p.getIntValue();
        /* Set the instance counter to the current document's id + 1 if it isn't already higher.  This will (weakly)
         * ensure that any newly created documents don't collide with whatever we're reading in.
         *
         * TODO: these ids are awful, and are certainly something I expect to regret implementing.  We should use
         * something better, make ids cleanly transient, or try to get away from ids at all.
         */
        instanceCounter.getAndUpdate(x -> Integer.max(x, thisId + 1));
        return thisId;
      }
    }

    private void readObject(java.io.ObjectInputStream in)
        throws IOException, ClassNotFoundException {
      in.defaultReadObject();
      // See comment in JSON id serializer re: why we need special id handling.
      instanceCounter.getAndUpdate(x -> Integer.max(x, getId() + 1));
    }
  }

  /**
   * A MassChargeMap is built from a list of MZSources, and stores the monoisotopic and ionic mass variants for each
   * of those sources.
   */
  public static class MassChargeMap implements Serializable {
    private static final long serialVersionUID = -332580987490663497L;

    // Tracks the monoisotopic mass for a given source, which can then be used to find collisions in the reverse map.
    Map<MZSource, Double> monoisotopicMasses;
    // Maps each monoisotopic mass to any colliding sources.
    Map<Double, List<MZSource>> reverseMonoisotopicMasses = new HashMap<>();

    // For a given ionic mass, it is a <ION_TYPE> for mass <MONOISOTOPIC MASS>, e.g., M+H for
    Map<Double, List<Pair<String, Double>>> reverseIonicMasses = new HashMap<>();

    // Package-private again.
    MassChargeMap() {
    }

    public List<MZSource> monoMassToMZSources(Double monoisotopicMass) {
      return Collections.unmodifiableList(reverseMonoisotopicMasses.get(monoisotopicMass));
    }

    public List<Pair<String, Double>> ionMZtoMonoMassAndIonName(Double mz) {
      return Collections.unmodifiableList(reverseIonicMasses.get(mz));
    }

    public Set<MZSource> ionMZToMZSources(Double mz) {
      List<Pair<String, Double>> reverseIons = reverseIonicMasses.get(mz);
      if (reverseIons == null) {
        return Collections.emptySet();
      }
      return Collections.unmodifiableSet(
          reverseIons.stream().
              map(p -> reverseMonoisotopicMasses.get(p.getRight())).
              flatMap(List::stream). // Flattens stream of List<MZSource> into single stream of MZSource.
              collect(Collectors.toSet())
      );
    }

    /* Use Iterable -> Stream instead of Iterator, as Iterator doesn't play nicely with streams on its own.
     * Iterator + Stream.generate = java.util.NoSuchElementException = :(
     * http://stackoverflow.com/questions/24511052/how-to-convert-an-iterator-to-a-stream */
    public Iterable<MZSource> mzSourceIter() {
      return () -> monoisotopicMasses.keySet().iterator();
    }

    public Iterable<Double> monoisotopicMassIter() {
      return () -> reverseMonoisotopicMasses.keySet().iterator();
    }

    public Iterable<Double> ionMZIter() {
      return () -> reverseIonicMasses.keySet().iterator();
    }

    // Package private--this one can eat a whole lot of memory, so we prefer the streaming approaches.
    List<Double> ionMZsSorted() {
      List<Double> results = new ArrayList<>(reverseIonicMasses.keySet());
      Collections.sort(results);
      // The keySet has already unique'd all the values, so all we need to do is sort 'em and return 'em.
      return Collections.unmodifiableList(results); // Make the list unmodifiable for safety's sake (we own the masses).
    }

    // Package private again.
    void loadSourcesAndMasses(List<Pair<MZSource, Double>> sourcesAndMasses, Set<String> onlyConsiderIons)
        throws IOException {
      monoisotopicMasses = new LinkedHashMap<>(sourcesAndMasses.size()); // Preserve order for easier testing.

      LOGGER.info("Converting %d m/z sources into ionic masses and resolving collisions", sourcesAndMasses.size());
      int sourceCounter = 0;
      int ionCounter = 0;
      for (Pair<MZSource, Double> sourceAndMass : sourcesAndMasses) {
        MZSource source = sourceAndMass.getLeft();
        Double monoMass = sourceAndMass.getRight();
        monoisotopicMasses.put(source, monoMass);

        sourceCounter++;
        if (sourceCounter % 1000 == 0) {
          LOGGER.info("Resolved %d sources, handled %d ion m/z's in total", sourceCounter, ionCounter);
        }

        if (reverseMonoisotopicMasses.containsKey(monoMass)) {
          reverseMonoisotopicMasses.get(monoMass).add(source);
          /* And we're done!  We've already computed the ions for this monoisotopic mass, so we've just squashed a
           * duplicate.  The source -> results correspondence will come out when we map the hits over the ionic masses,
           * then tie those back to their corresponding MZSources. */
          continue;
        }

        List<MZSource> sourcesWithThisMass = new ArrayList<MZSource>(1) {{
          add(source);
        }};
        reverseMonoisotopicMasses.put(monoMass, sourcesWithThisMass);

        Map<String, Double> ions = MS1.getIonMasses(monoMass, MS1.IonMode.POS);
        // Filter to only the specified ions if the onlyConsider set is non-empty; allow all ions by default.
        if (onlyConsiderIons.size() != 0) {
          ions = ions.entrySet().stream().
              filter(e -> onlyConsiderIons.contains(e.getKey())).
              collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        for (Map.Entry<String, Double> ion : ions.entrySet()) {
          List<Pair<String, Double>> reverseMapping = reverseIonicMasses.get(ion.getValue());
          if (reverseMapping == null) {
            reverseMapping = new ArrayList<>(1); // Assume few collisions.
            reverseIonicMasses.put(ion.getValue(), reverseMapping);
          }
          reverseMapping.add(Pair.of(ion.getKey(), monoMass));
          ionCounter++;
        }
      }
      LOGGER.info("Done resolving %d sources, found %d ion m/z's in total", sourceCounter, ionCounter);
    }

    /**
     * Accumulates all of the MZSources associated with this ion and the ion name per each source for a given ion m/z.
     * @param ionicMassCharge The ion's m/z for which to search.
     * @return A list of each corresponding MZSource and the ionic variant the specified m/z represents for this source.
     *         Returns an empty list if no matching sources are found.
     */
    public List<Pair<MZSource, String>> mapIonMZToSources(Double ionicMassCharge) {
      List<Pair<String, Double>> reverseMapping = reverseIonicMasses.get(ionicMassCharge);
      if (reverseMapping == null) {
        return Collections.emptyList();
      }

      // Flattening this list with the stream API is a bit clumsy, so just loop over all the reverse mappings.
      List<Pair<MZSource, String>> results = new ArrayList<>();
      for (Pair<String, Double> ionNameAndMonoMass : reverseMapping) {
        List<MZSource> mzSources = reverseMonoisotopicMasses.get(ionNameAndMonoMass.getRight());
        for (MZSource source : mzSources) {
          results.add(Pair.of(source, ionNameAndMonoMass.getLeft()));
        }
      }

      return results;
    }
  }

  public static MassChargeMap makeMassChargeMap(List<MZSource> mzSources) throws IOException {
    return makeMassChargeMap(mzSources, Collections.emptySet());
  }

  public static MassChargeMap makeMassChargeMap(List<MZSource> mzSources, Set<String> onlyConsiderIons)
      throws IOException {
    MassChargeMap map = new MassChargeMap();
    /* Map over the sources, extracting or computing the mass as we go to encapsulate any unsafe behavior outside the
     * MassChargeMap constructor. */
    List<Pair<MZSource, Double>> sourcesAndMasses = new ArrayList<>();
    for (MZSource source : mzSources) {
      try {
        sourcesAndMasses.add(Pair.of(source, computeMass(source)));
      } catch (Exception e) {
        LOGGER.error("MZSource %d threw an error during mass calculation, skipping", source.getId());
      }
    }
    LOGGER.info("Consumed %d mzSources, sourcesAndMasses has %d entries", mzSources.size(), sourcesAndMasses.size());
    map.loadSourcesAndMasses(sourcesAndMasses, onlyConsiderIons);
    return map;
  }

  protected static Double computeMass(MZSource mzSource) {
    Double mass;
    String msg;
    switch (mzSource.getKind()) {
      case INCHI:
        Pair<Double, Integer> massAndCharge;
        try {
          massAndCharge = MassCalculator.calculateMassAndCharge(mzSource.getInchi());
        } catch (Exception e) {
          LOGGER.error("Calculating mass for molecule %s failed: %s", mzSource.getInchi(), e.getMessage());
          throw e;
        }
        if (massAndCharge.getRight() > 0) {
          LOGGER.warn("(MZSrc %d) Molecule %s has a positive charge %d; ionization may not have the expected effect",
              mzSource.getId(), mzSource.getInchi(), massAndCharge.getRight());
        } else if (massAndCharge.getRight() < 0) {
          LOGGER.warn("(MZSrc %d) Molecule %s has a negative charge %d; it may not fly in positive ion mode",
              mzSource.getId(), mzSource.getInchi(), massAndCharge.getRight());
        }
        mass = massAndCharge.getLeft();
        break;
      case ARBITRARY_MASS:
        mass = mzSource.getArbitraryMass();
        break;
      case PRE_EXTRACTED_MASS:
        mass = mzSource.getPreExtractedMass().getRight();
        break;
      case UNKNOWN:
        msg = String.format("mzSource with id %d has UNKNOWN kind, which is invalid", mzSource.getId());
        LOGGER.error(msg);
        throw new RuntimeException(msg);
      default:
        msg = String.format("mzSource with id %d has unknown kind (should be impossible): %s",
            mzSource.getId(), mzSource.getKind());
        LOGGER.error(msg);
        throw new RuntimeException(msg);
    }
    return mass;
  }
}
