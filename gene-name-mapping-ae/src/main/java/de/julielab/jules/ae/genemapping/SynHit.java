/**
 * SynHit.java
 * <p>
 * Copyright (c) 2006, JULIE Lab.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * <p>
 * Author: tomanek, wermter
 * <p>
 * Current version: 2.0
 * Since version:   1.0
 * <p>
 * Creation date: Dec 6, 2006
 * <p>
 * An object used to store mapping results.
 **/

package de.julielab.jules.ae.genemapping;

import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import de.julielab.jules.ae.genemapping.genemodel.GeneName;
import de.julielab.jules.ae.genemapping.scoring.Scorer;

public class SynHit implements Comparable<SynHit>, Cloneable {

    /*
     * this is a random id used for sorting
     */
    int random;
    private String synonym;

    private double mentionScore;
    private double semanticScore;
    private double overallScore;
    private Map<String, Double> speciesMentionScores = new HashMap<>();
    /**
     * All known Entrez Gene IDs for this synonym.
     */
    private List<String> ids;
    /**
     * Set by {@link #setTaxId(String)}. Contains all the IDs associated with the set taxonomy ID. This will
     * be unique in most cases but sometimes it isn't.
     */
    private String[] taxonomySpecificIds;
    private String id;
    private String source;
    private String mappedMention; // the mention found in text and searched for
    // compare type is used during scoring if two synsets have same score
    // (see in compareTo(...) method)
    private CompareType compareType = CompareType.SCORE;
    private List<String> taxIds; // NCBI Taxonomy ID
    private String taxId;
    private GeneName mappedGeneName;
    private List<Number> synonymPriorities;

    public SynHit(String synonym, double score, List<String> ids, String source, List<String> taxIds) {
        this.synonym = synonym;
        this.mentionScore = score;
        this.ids = ids;
        this.source = source;
        this.taxIds = taxIds;
    }

    /**
     * @param syn
     * @param score
     * @param xid
     * @param source
     */
    public SynHit(String syn, double score, String xid, String source) {
        this.synonym = syn;
        this.mentionScore = score;
        this.ids = Arrays.asList(xid);
        this.source = source;
    }

    public Map<String, Double> getSpeciesMentionScores() {
        return speciesMentionScores;
    }

    public Double getSpeciesMentionScore(String taxId) {
        return speciesMentionScores.get(taxId);
    }

    public void setSpeciesMentionScore(String taxId, double speciesMentionScore) {
        speciesMentionScores.put(taxId, speciesMentionScore);
    }

    public void restrictToTaxId(String taxId) {
        this.id = null;
        this.taxId = null;
        for (int i = 0; i < ids.size(); i++) {
            if (taxIds.get(i).equals(taxId)) {
                this.id = ids.get(i);
                this.taxId = taxId;
            }
        }
        if (this.id == null)
            throw new IllegalArgumentException("This SynHit does not contain taxonomy ID " + taxId + ": " + this);
    }

    /**
     * @return
     */
    public double getMentionScore() {
        return mentionScore;
    }

    public void setMentionScore(double score) {
        this.mentionScore = score;
    }

    public double getSemanticScore() {
        return this.semanticScore;
    }

    public void setSemanticScore(double score) {
        this.semanticScore = score;
    }

    public String getSynonym() {
        return synonym;
    }

    public void setSynonym(String syn) {
        this.synonym = syn;
    }

    public String toString() {
        DecimalFormat scoreFormat = new DecimalFormat("0.000");
        String result = "syn=" + synonym + "\tid=" + ids + "\tscore=" + scoreFormat.format(mentionScore) + "\tsemScore="
                + scoreFormat.format(semanticScore) + "\ttaxId="
                + taxIds;
        return result;
    }

    /**
     * the comparator for two SynHits: order by score as set by setCompareType
     * method TODO: find rule how to order if several SynHits have same score
     * currently, random number is chosen
     *
     * @param o
     * @return int
     */
    public int compareTo(SynHit o) {
        int c = 0;
        if (this.compareType != o.compareType)
            throw new IllegalStateException(
                    "Two SynHits are compared that don't use the same comparison type: " + this + ", " + o);
        // int c = (new Double(s.getScore())).compareTo(new
        // Double(this.getScore()));
        // if (c == 0) {
        // in case of same score:
        switch (this.compareType) {
            case RANDOM:
                c = (new Integer(o.random)).compareTo(this.random);
                break;
            case SCORE:
                c = Double.compare(o.mentionScore, mentionScore);
                break;
            case SEMSCORE:
                c = (new Double(o.semanticScore)).compareTo(this.semanticScore);
                break;
        }
        // }
        return c;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public CompareType getCompareType() {
        return compareType;
    }

    public void setCompareType(CompareType type) {
        this.compareType = type;
    }

    /**
     * The potentially normalized and/or transformed original entity text
     * mention for which this candidate has been retrieved.
     *
     * @return The string-normalized entity name that this candidate was matched
     * to.
     */
    public String getMappedMention() {
        return mappedMention;
    }

    public void setMappedMention(String mappedSynonym) {
        this.mappedMention = mappedSynonym;
    }

    public boolean isExactMatch() {
        return mentionScore == Scorer.PERFECT_SCORE;
    }

    public SynHit clone() throws CloneNotSupportedException {
        SynHit h = (SynHit) super.clone();
        h.speciesMentionScores = new HashMap<>(speciesMentionScores);
        h.ids = new ArrayList<>(ids);
        h.taxIds = new ArrayList<>(getTaxIds());
        h.synonymPriorities = new ArrayList<>(synonymPriorities);
        return h;
    }

    public List<String> getIds() {
        return ids;
    }

    public void setIds(List<String> ids) {
        this.ids = ids;
    }

    /**
     * <p>Returns <tt>true</tt> if a single gene or protein ID of this synonym has been determined.</p>
     *
     * @return <tt>true</tt> if the final gene/protein ID of this synonym has been set, <tt>false</tt> otherwise.
     */
    public boolean isDisambiguated() {
        return id != null;
    }

    /**
     * <p>Returns <tt>true</tt> if there is more than one gene ID associated with this synonym.</p>
     *
     * @return Whether there are multiple gene IDs for this synonym.
     */
    public boolean isAmbiguousInGeneral() {
        return ids.size() > 1;
    }

    /**
     * <p>Returns <tt>true</tt> if at least one taxonomy ID associated with this synonym appears multiple times.</p>
     *
     * @return Whether this synonym exists for multiple different genes of the same species.
     */
    public boolean isIntraSpeciesAmbiguousInGeneral() {
        Set<String> seenTaxIds = new HashSet<>();
        boolean currentTaxIdWasNotYetSeen = false;
        for (int i = 0; i < taxIds.size() && (currentTaxIdWasNotYetSeen = seenTaxIds.add(taxIds.get(i))); i++) ;
        return !currentTaxIdWasNotYetSeen;
    }

    /**
     * <p>Returns <tt>true</tt> if there are at least two distinct taxonomy IDs associated with this synonym.</p>
     *
     * @return Whether there are multiple different species that have a gene with this synonym.
     */
    public boolean isInterSpeciesAmbiguousInGeneral() {
        if (taxIds.size() <= 1)
            return false;
        Set<String> seenTaxIds = new HashSet<>();
        seenTaxIds.add(taxIds.get(0));
        boolean currentTaxIdWasNotYetSeen = false;
        for (int i = 1; i < taxIds.size() && !(currentTaxIdWasNotYetSeen = seenTaxIds.add(taxIds.get(i))); i++) ;
        return currentTaxIdWasNotYetSeen;
    }

    public String getId() {
        if (id == null) {
            if (taxonomySpecificIds != null && taxonomySpecificIds.length > 0)
                return taxonomySpecificIds[0];
            if (ids.size() == 1)
                return ids.get(0);
        }
        return id;
    }

    public void setId(String id) {

        this.id = id;
    }

    public List<String> getTaxIds() {
        return taxIds;
    }

    public void setTaxIds(List<String> taxIds) {
        this.taxIds = taxIds;
    }

    public double getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(double overallScore) {
        this.overallScore = overallScore;
    }

    public GeneName getMappedGeneName() {
        return mappedGeneName;
    }

    public void setMappedGeneName(GeneName mappedGeneName) {
        this.mappedGeneName = mappedGeneName;

    }

    /**
     * <p>Returns this single accepted taxonomy ID for this synonym (depends on the document context and may be differ
     * for different textual occurrences of this synonym) or <tt>null</tt> if not set.</p>
     * <p>The taxonomy ID is set by {@link #setTaxId(String)}.</p>
     *
     * @return The taxonomy ID associated with this synonym or <tt>null</tt> if it wasn't successfully set.
     * @see #setTaxId(String)
     * @see #getTaxIds()
     */
    public String getTaxId() {
        return taxId;
    }

    /**
     * <p>Accepts the passed taxonomy ID as assigned to this synonym. This causes the {@link #taxonomySpecificIds} field
     * to be set which can be retrieved using {@link #getTaxonomySpecificIds()}. In case that the taxonomy ID
     * assignment unique identifies a single gene/protein ID, this ID will be set to the {@link #id} field, marking
     * this synonym as being disambiguated.</p>
     *
     * @param taxId The taxonomy ID to assign this synonym.
     * @throws IllegalArgumentException If the given taxonomy ID cannot be set to this synonym because it does not exist for the given tax ID.
     * @see #getTaxonomySpecificIds()
     * @see #getId()
     * @see #isDisambiguated()
     */
    public void setTaxId(String taxId) {
        final int[] indices = IntStream.range(0, taxIds.size()).filter(i -> taxIds.get(i).equals(taxId)).toArray();
        if (indices.length == 0)
            throw new IllegalArgumentException("Cannot set taxonomy ID '" + taxId + "' to this SynHit because this taxonomy ID does not occur for this SynHit.");
        taxonomySpecificIds = IntStream.of(indices).mapToObj(i -> String.valueOf(ids.get(i))).toArray(String[]::new);
        if (indices.length == 1)
            this.id = taxonomySpecificIds[0];
        this.taxId = taxId;
    }

    public String[] getTaxonomySpecificIds() {
        return taxonomySpecificIds;
    }

    public List<Number> getPrioritiesOfIds(String[] idArray) {
        return getPrioritiesOfIds(Stream.of(idArray));
    }

    public List<Number> getPrioritiesOfIds(Stream<String> idStream) {
        final Set<String> idSet = idStream.collect(Collectors.toSet());
        return IntStream.range(0, ids.size()).filter(i -> idSet.contains(ids.get(i))).mapToObj(synonymPriorities::get).collect(Collectors.toList());
    }

    public boolean hasTaxId(String taxId) {
        return taxIds.indexOf(taxId) != -1;
    }

    public List<Number> getSynonymPriorities() {
        return synonymPriorities;
    }

    public void setSynonymPriorities(List<Number> synonymPriorities) {
        this.synonymPriorities = synonymPriorities;
    }

    public int getSynonymPriority() {
        return synonymPriorities.get(0).intValue();
    }

    public Stream<String> getGeneIdsOfTaxId(String taxId) {
        if (taxIds != null)
            return IntStream.range(0, taxIds.size()).filter(i -> taxIds.get(i).equals(taxId)).mapToObj(ids::get);
        return Stream.empty();
    }

    public enum CompareType {
        RANDOM, SCORE, SEMSCORE
    }

}
