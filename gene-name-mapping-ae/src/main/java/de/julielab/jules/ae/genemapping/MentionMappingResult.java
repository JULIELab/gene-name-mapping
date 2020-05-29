package de.julielab.jules.ae.genemapping;

import de.julielab.jules.ae.genemapping.genemodel.GeneIdCandidate;
import de.julielab.jules.ae.genemapping.genemodel.GeneMention;

import java.util.Arrays;
import java.util.List;

public class MentionMappingResult implements Comparable<MentionMappingResult> {
    public static final List<SynHit> REJECTION = Arrays.asList(new RejectionSynHit("GENE MENTION REJECTED", 0d,GeneMention.NOID,
                                                                            GeneMapping.class.getSimpleName()));
    public List<SynHit> resultEntries;
    public int ambiguityDegree;
    public MatchType matchType;
    public GeneMention mappedMention;

    /**
     * <p>
     * This field is set towards the end of the mapping/disambiguation process. A list of gene IDs will have been
     * identified that look like they could be apply the gene mention. The list should always be ordered descending
     * in score, meaning that the first entry would be the best to choose.
     * </p>
     * <p>If this is only one candidate,
     * the mention is unambiguously mappable (which doesn't mean to 100% that the mapping will be correct, but
     * all the hints we have point to this single candidate).
     * </p>
     * <p>If there are multiple candidates, we still haven't finally decided to this point.</p>
     */
    public List<GeneIdCandidate> geneIdCandidates;
    /**
     * The database candidates found for the name of this gene mention. This is
     * a list of database name matches ordered by the score that the specific
     * database name matches the gene name.
     */
    public List<SynHit> originalCandidates;
    /**
     * The candidates that have been filtered by some criterion in the attempt
     * to eliminate bad candidates. The list has to be set by an external algorithm and will be
     * null before that.
     */
    public List<SynHit> filteredCandidates;
    /**
     * This list contains the same elements as {@link #filteredCandidates} but
     * sorted for semantic score, after the respective external algorithm in a
     * disambiguation class has set this field.
     */
    public List<SynHit> semanticallyOrderedCandidates;
    /**
     * The best candidate at the current time of the mapping process, defaults
     * to {@link #REJECTION}.
     */
    public List<SynHit> bestCandidate = REJECTION;
    public double confidence;
    private long candidateRetrievalTime;
    private long disambiguationTime;

    /**
     * The comparison is delegated to the order of the resultEntry SynHits.
     * Thus, we actually sort by SynHit.
     */
    @Override
    public int compareTo(MentionMappingResult o) {
        return resultEntries.get(0).compareTo(o.resultEntries.get(0));
    }

    public long getCandidateRetrievalTime() {
        return candidateRetrievalTime;
    }

    public void setCandidateRetrievalTime(long candidateRetrievalTime) {
        this.candidateRetrievalTime = candidateRetrievalTime;
    }

    public long getDisambiguationTime() {
        return disambiguationTime;
    }

    public void setDisambiguationTime(long disambiguationTime) {
        this.disambiguationTime = disambiguationTime;

    }

    public enum MatchType {
        APPROX, EXACT
    }

    public static class RejectionSynHit extends SynHit {
        private RejectionSynHit(String syn, double score, String xid, String source) {
            super(syn, score, xid, source);
        }

        @Override
        public double getMentionScore() {
            // when in doubt about correctness of this value, see https://stackoverflow.com/questions/3884793/why-is-double-min-value-in-not-negative
            return -Double.MAX_VALUE;
        }

        @Override
        public String getSynonym() {
            throw new IllegalStateException(
                    "This is the rejection SynHit, it should only be used as a constant to check if a gene mention is rejected.");
        }

        @Override
        public String getSource() {
            throw new IllegalStateException(
                    "This is the rejection SynHit, it should only be used as a constant to check if a gene mention is rejected.");
        }

        @Override
        public String getMappedMention() {
            throw new IllegalStateException(
                    "This is the rejection SynHit, it should only be used as a constant to check if a gene mention is rejected.");
        }

        @Override
        public boolean isExactMatch() {
            return false;
        }

        @Override
        public List<String> getTaxIds() {
            throw new IllegalStateException(
                    "This is the rejection SynHit, it should only be used as a constant to check if a gene mention is rejected.");
        }
    }

}
