package de.julielab.jules.ae.genemapping.genemodel;

import java.util.*;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import de.julielab.java.utilities.prerequisites.PrerequisiteChecker;
import de.julielab.java.utilities.spanutils.Span;
import de.julielab.jules.ae.genemapping.SynHit;
import org.apache.commons.lang3.Range;
import org.apache.lucene.search.Query;

import cc.mallet.types.FeatureVector;
import de.julielab.jules.ae.genemapping.MentionMappingResult;
import de.julielab.jules.ae.genemapping.utils.norm.TermNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * A basic "gene mention" that most of all contains the text of the mention.
 * However, we might also need other information, i.e. offsets.
 *
 * @author faessler
 */
public class GeneMention implements Span {
private final static Logger log = LoggerFactory.getLogger(GeneMention.class);
    /**
     * Constant meaning that no ID is given for a GeneMention.
     */
    public static final String NOID = "NoId";
    private Object originalMappedObject;

    /**
     * <p>The original UIMA annotation that is mapped. Most likely a subclass of EntityMention.</p>
     * @return The original object to be mapped.
     */
    public Object getOriginalMappedObject() {
        return originalMappedObject;
    }

    public void setOriginalMappedObject(Object originalMappedObject) {
        this.originalMappedObject = originalMappedObject;
    }

    private String docId;

    private GeneName geneName;

    private String id = NOID;

    private TermNormalizer normalizer;

    private Range<Integer> offsets;

    private String text;
    private String goldTaxonomyId;
    private String taxonomyId;
    private Map<String, Double> taxonomyScores;
    private GeneSpeciesOccurrence taxonomyReliability;
    private Multimap<String, GeneSpeciesOccurrence> taxonomyCandidates = HashMultimap.create();
    private String documentContext;
    private Query contextQuery;
    private GeneTagger tagger;
    private SpecificType specificType = SpecificType.GENE;
    private double specificTypeConfidence;
    private MentionMappingResult mentionMappingResult;
    private GeneDocument geneDocument;
    private List<String> taggingModifiers;
    /**
     * A parent GeneMention is a GeneMention that has been split into sub-mentions,
     * most commonly due to conjunctions or enumerations within a GeneMention. Thus,
     * when parent is not null, this GeneMention resulted from a split of another
     * GeneMention.
     */
    private GeneMention parent;
    private List<PosTag> posTags;
    private FeatureVector featureVector;
    private String reducedNameForExactMatch;

    public GeneMention() {
    }

    /**
     * Makes a copy of the given GeneMention but NOT from its MentionMappingResult.
     *
     * @param gm The gene mention to copy.
     */
    public GeneMention(GeneMention gm) {
        this.contextQuery = gm.contextQuery;
        this.docId = gm.docId;
        this.documentContext = gm.documentContext;
        this.geneDocument = gm.geneDocument;
        this.id = gm.id;
        this.normalizer = gm.normalizer;
        this.offsets = gm.offsets;
        this.tagger = gm.tagger;
        this.taxonomyCandidates = gm.taxonomyCandidates;
        this.taxonomyId = gm.taxonomyId;
        this.text = gm.text;
    }

    public GeneMention(String text) {
        this.text = text;
    }

    public GeneMention(String text, TermNormalizer normalizer) {
        this(text);
        this.setNormalizer(normalizer);
    }

    public GeneMention(String text, int begin, int end) {
        this(text);
        this.offsets = Range.between(begin, end);
    }

    public String getGoldTaxonomyId() {
        return goldTaxonomyId;
    }

    public void setGoldTaxonomyId(String goldTaxonomyId) {
        this.goldTaxonomyId = goldTaxonomyId;
    }

    public void setTaxonomyScore(String tax, double score) {
        if (taxonomyScores == null)
            taxonomyScores = new HashMap<>();
        taxonomyScores.put(tax, score);
    }

    public double getTaxonomyScore(String taxonomyId) {
        return taxonomyScores == null ? 0 : taxonomyScores.getOrDefault(taxonomyId, 0d);
    }

    public Map<String, Double> getTaxonomyScores() {
        return taxonomyScores;
    }

    public void setTaxonomyScores(Map<String, Double> taxonomyScores) {
        this.taxonomyScores = taxonomyScores;
    }

    public GeneSpeciesOccurrence getTaxonomyReliability() {
        return taxonomyReliability;
    }

    public void setTaxonomyReliability(GeneSpeciesOccurrence taxonomyReliability) {
        this.taxonomyReliability = taxonomyReliability;
    }

    public List<String> getTaggingModifiers() {
        return taggingModifiers;
    }

    /**
     * Sets the currently highest-scoring taxonomy ID to the current bestcandidate of
     * the <tt>mentionMappingResult</tt>, which also causes the <tt>bestcandidate</tt> to set the
     * corresponding gene/protein ID as its ID.
     * <p>NOTE: If no gene name could be found by the taxonomy score assignment component that matched any of the
     * taxonomy candidates, the taxonomy scores will be null. Consequently, no taxonomy ID will be set
     * to the mention mapping result.</p>
     */
    public void acceptHighestScoringTaxForBestMappingCandidate() {
        // It seems that no gene database entry was found for any of the candidate species mentions in
        // the document. Nothing to do here.
        if (taxonomyScores == null || taxonomyScores.isEmpty())
            return;
        PrerequisiteChecker.checkThat()
                .notNull(mentionMappingResult)
                .supplyNotNull(() -> mentionMappingResult.bestCandidate)
                .withNames("taxonomy scores", "taxonomy scores", "mention mapping result", "mention mapping result").execute();
        double bestScore = 0;
        String bestTax = null;
        for (String taxId : taxonomyScores.keySet()) {
            final Double score = taxonomyScores.get(taxId);
            if (score > bestScore) {
                bestScore = score;
                bestTax = taxId;
            }
        }
        boolean foundCandidateWithTax = setTaxonomyIdToMentionMappingResult(bestTax);
        if (!foundCandidateWithTax) {
            // So in the whole candidate list the taxonomy ID cannot be found. Probably the
            // taxonomy ID is just wrong (or this is not really a gene or some other error causing this).
            // Try the a priori default taxonomy for this gene name.
            final String defaultSpecies = getGeneDocument().getDefaultSpecies();
            final boolean foundCandidateWithDefaultTax = setTaxonomyIdToMentionMappingResult(defaultSpecies);
            if (!foundCandidateWithDefaultTax) {
                log.warn("Could not set the best scored taxonomy ID {} or the default taxonomy ID {} to the candidates of the gene mention because no candidate applies to one of those IDs. The gene mention is {}", bestTax, defaultSpecies, this);
            }
        }
    }

    private boolean setTaxonomyIdToMentionMappingResult(String bestTax) {
        final List<SynHit> bestCandidate = mentionMappingResult.bestCandidate;
        boolean foundCandidateWithTax = false;
        try {
            bestCandidate.get(0).setTaxId(bestTax);
            foundCandidateWithTax = true;
        } catch (IllegalArgumentException e) {
            // This means that the taxonomy ID is not present for the best candidate. Search for
            // a new best candidate that has the ID
            for (int i = 0; i < mentionMappingResult.filteredCandidates.size(); i++) {
                final SynHit synHit = mentionMappingResult.filteredCandidates.get(i);
                try {
                    synHit.setTaxId(bestTax);
                    mentionMappingResult.bestCandidate.set(0, synHit);
                    foundCandidateWithTax = true;
                } catch (IllegalArgumentException e1) {
                    // Nothing, this SynHit was also not applicable for the given ID.
                    // If all fails, the original bestcandidate will be kept, for better or for worse.
                }
            }
        }
        return foundCandidateWithTax;
    }

    public String getTaxonomyId() {
        return taxonomyId;
    }

    public void setTaxonomyId(String taxonomyId) {
        this.taxonomyId = taxonomyId;
    }

    public Set<String> getTaxonomyIds() {
        if(taxonomyCandidates != null)
            return taxonomyCandidates.keySet();
        if (taxonomyId != null)
            return Collections.singleton(taxonomyId);
        return Collections.emptySet();
    }

    public Multimap<String, GeneSpeciesOccurrence> getTaxonomyCandidates() {
        return taxonomyCandidates;
    }

    public void setTaxonomyCandidates(Multimap<String, GeneSpeciesOccurrence> taxonomyCandidates) {
        this.taxonomyCandidates = taxonomyCandidates;
    }

    public String getDocumentContext() {
        return documentContext;
    }

    public void setDocumentContext(String documentContext) {
        this.documentContext = documentContext;
    }

    public Query getContextQuery() {
        return contextQuery;
    }

    public void setContextQuery(Query contextQuery) {
        this.contextQuery = contextQuery;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((docId == null) ? 0 : docId.hashCode());
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        result = prime * result + ((offsets == null) ? 0 : offsets.hashCode());
        result = prime * result + ((tagger == null) ? 0 : tagger.hashCode());
        result = prime * result + ((taxonomyId == null) ? 0 : taxonomyId.hashCode());
        result = prime * result + ((text == null) ? 0 : text.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        GeneMention other = (GeneMention) obj;
        if (docId == null) {
            if (other.docId != null)
                return false;
        } else if (!docId.equals(other.docId))
            return false;
        if (id == null) {
            if (other.id != null)
                return false;
        } else if (!id.equals(other.id))
            return false;
        if (offsets == null) {
            if (other.offsets != null)
                return false;
        } else if (!offsets.equals(other.offsets))
            return false;
        if (tagger != other.tagger)
            return false;
        if (taxonomyId == null) {
            if (other.taxonomyId != null)
                return false;
        } else if (!taxonomyId.equals(other.taxonomyId))
            return false;
        if (text == null) {
            if (other.text != null)
                return false;
        } else if (!text.equals(other.text))
            return false;
        return true;
    }

    public int getBegin() {
        return offsets.getMinimum();
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public int getEnd() {
        return offsets.getMaximum();
    }

    public GeneName getGeneName() {
        if (geneName == null && getNormalizer() == null)
            throw new IllegalStateException(
                    "This GeneMention has not set a TermNormalizer and thus cannot create a GeneName instance.");
        if (geneName == null)
            geneName = new GeneName(text, normalizer);
        return geneName;
    }

    /**
     * The gene ID of this gene mention. This is mostly used for evaluation and
     * might not be set in a variety of situations. During the mapping process the
     * final mapped ID of this mention is determined by the mention mapping result,
     * see {@link #getMentionMappingResult()}.
     *
     * @return The gene ID of this mention, if set.
     */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public TermNormalizer getNormalizer() {
        return normalizer;
    }

    public void setNormalizer(TermNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public Range<Integer> getOffsets() {
        return offsets;
    }

    public void setOffsets(Range<Integer> offsets) {
        this.offsets = offsets;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
        if (normalizer != null)
            getGeneName().setText(text);
    }

    @Override
    public String toString() {
        return "GeneMention [text=" + text + ", offsets=" + offsets + ", docId=" + docId + ", id=" + id
                + ", taxonomyId=" + taxonomyId + ", tagger=" + tagger + "]";
    }

    public String getNormalizedText() {
        return getGeneName().getNormalizedText();
    }

    public List<String> getNormalizedTextVariant() {
        return getGeneName().getNormalizedTextVariant();
    }

    public GeneTagger getTagger() {
        return tagger;
    }

    public void setTagger(GeneTagger tagger) {
        this.tagger = tagger;
    }

    /**
     * @return The object representing the result of the mapping process for this
     * particular gene mention.
     */
    public MentionMappingResult getMentionMappingResult() {
        return mentionMappingResult;
    }

    public void setMentionMappingResult(MentionMappingResult mentionMappingResult) {
        this.mentionMappingResult = mentionMappingResult;
    }

    public GeneDocument getGeneDocument() {
        return geneDocument;
    }

    public void setGeneDocument(GeneDocument geneDocument) {
        this.geneDocument = geneDocument;
    }

    /**
     * A parent GeneMention is a GeneMention that has been split into sub-mentions,
     * most commonly due to conjunctions or enumerations within a GeneMention. Thus,
     * when parent is not null, this GeneMention resulted from a split of another
     * GeneMention.
     *
     * @return The GeneMention that has been split to produce this - and possibly
     * other - GeneMention(s).
     */
    public GeneMention getParent() {
        return parent;
    }

    public void setParent(GeneMention parent) {
        this.parent = parent;
    }

    public void addTaggingModifier(String modifier) {
        if (taggingModifiers == null)
            taggingModifiers = new ArrayList<>();
        taggingModifiers.add(modifier);
    }

    public List<PosTag> getPosTags() {
        return posTags;
    }

    public void setPosTags(List<PosTag> posTags) {
        this.posTags = posTags;
    }

    public SpecificType getSpecificType() {
        return specificType;
    }

    public void setSpecificType(SpecificType specificType) {
        this.specificType = specificType;
    }

    public FeatureVector getFeatureVector() {
        return featureVector;
    }

    public void setFeatureVector(FeatureVector featureVector) {
        this.featureVector = featureVector;

    }

    public double getSpecificTypeConfidence() {
        return specificTypeConfidence;
    }

    public void setSpecificTypeConfidence(double specificTypeConfidence) {
        this.specificTypeConfidence = specificTypeConfidence;
    }

    public String getReducedNameForExactMatch() {
        return reducedNameForExactMatch;
    }

    public void setReducedNameForExactMatch(String reducedNameForExactMatch) {
        this.reducedNameForExactMatch = reducedNameForExactMatch;
    }


    public enum GeneTagger {
        JNET, GAZETTEER, BANNER, GOLD
    }

    public enum SpecificType {
        GENE, FAMILYNAME, DOMAINMOTIF
    }

}
