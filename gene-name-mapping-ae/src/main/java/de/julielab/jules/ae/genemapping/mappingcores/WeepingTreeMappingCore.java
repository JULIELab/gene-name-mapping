package de.julielab.jules.ae.genemapping.mappingcores;

import de.julielab.jules.ae.genemapping.*;
import de.julielab.jules.ae.genemapping.disambig.SemanticDisambiguation;
import de.julielab.jules.ae.genemapping.disambig.WeepingTreeDisambiguation;
import de.julielab.jules.ae.genemapping.disambig.WeepingTreeDocumentDisambiguationData;
import de.julielab.jules.ae.genemapping.genemodel.GeneDocument;
import de.julielab.jules.ae.genemapping.genemodel.GeneMention;
import de.julielab.jules.ae.genemapping.utils.GeneMappingException;
import de.julielab.jules.ae.genemapping.utils.norm.TermNormalizer;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * <p>A rather relaxed mapping that doesn't try hard to disambiguate and does not filter out gene families or domains.
 * This is basically just a gene synonym expansion mapping with minor name disambiguation at best.</p>
 */
public class WeepingTreeMappingCore implements MappingCore {
    /**
     * Configuration parameter. Lists NCBI taxonomy IDs separated by commas. If this is given, only
     * gene candidates belonging to at least one of the given taxonomy IDs will be taken into account.
     */
    public static final String TAX_IDS = "filter_tax_ids";
    private final Set<String> filterTaxIds;
    private final WeepingTreeDisambiguation disambiguation;
    private TermNormalizer normalizer;
    private LuceneCandidateRetrieval candidateRetrieval;

    public WeepingTreeMappingCore(GeneMappingConfiguration configuration) throws GeneMappingException {
        this.normalizer = new TermNormalizer();
        this.candidateRetrieval = new LuceneCandidateRetrieval(configuration);
        filterTaxIds = Stream.of(configuration.getProperty(TAX_IDS, "").split(",")).map(String::trim).filter(Predicate.not(String::isBlank)).collect(Collectors.toSet());
        if (filterTaxIds.isEmpty())
            throw new GeneMappingException("Missing configuration property '" + TAX_IDS + "'. You must specify at least one taxonomy ID to which all gene mentions should be mapped. You can specify multiple possibilities by providing a comma separated list of tax IDs.");
        disambiguation = new WeepingTreeDisambiguation(configuration);
    }

    @Override
    public MentionMappingResult map(GeneMention geneMention) throws GeneMappingException {
        final List<SynHit> candidates = candidateRetrieval.getCandidates(geneMention);
        final MentionMappingResult mappingResult = new MentionMappingResult();
        mappingResult.originalCandidates = candidates;
        mappingResult.bestCandidate = getBestSynonyms(candidates, filterTaxIds);
        mappingResult.mappedMention = geneMention;
        mappingResult.resultEntries = MentionMappingResult.REJECTION;
        geneMention.setMentionMappingResult(mappingResult);
        return mappingResult;
    }

    /**
     * <p>Returns the candidates that are compatible with the passed taxonomy IDs and have the highest mention scores.</p>
     *
     * @param candidates  All synonym candidates.
     * @param taxonomyIDs The filter taxonomy IDs, may be empty.
     * @return The taxonomy compatible best-scoring candidates.
     */
    private List<SynHit> getBestSynonyms(List<SynHit> candidates, Set<String> taxonomyIDs) {
        if (candidates.isEmpty())
            return Collections.emptyList();
        double bestScore = candidates.get(0).getMentionScore();
        // collect all synonyms that share the best score (may also be approximate hits) and - if given - match
        // at least one filter taxonomy ID
        // First: Collect all candidates that are compatible with the given taxonomy IDs
        List<SynHit> ret = new ArrayList<>();
        final List<SynHit> candidatesForTaxIds = IntStream.range(0, candidates.size()).filter(i -> {
            if (!taxonomyIDs.isEmpty()) {
                // find any taxonomy ID of the candidate that is contained in the filter taxonomy IDs
                final Optional<String> foundTaxId = candidates.get(i).getTaxIds().stream().filter(taxonomyIDs::contains).findAny();
                return foundTaxId.isPresent();
            }
            return true;
        }).mapToObj(candidates::get).collect(Collectors.toList());
        if (candidatesForTaxIds.isEmpty())
            return Collections.emptyList();
        // If we still have candidates, get those with the highest score
        ret.add(candidatesForTaxIds.get(0));
        for (int i = 1; i < candidatesForTaxIds.size() && candidatesForTaxIds.get(i).getMentionScore() == candidatesForTaxIds.get(0).getMentionScore(); i++) {
            ret.add(candidatesForTaxIds.get(i));
        }
        final List<SynHit> hitsWithBestScore = candidatesForTaxIds.stream().filter(c -> c.getMentionScore() == bestScore).collect(Collectors.toList());
        // Now try to set one of the given taxonomy IDs to the remaining hits
        for (SynHit bestHit : hitsWithBestScore) {
            for (String tax : taxonomyIDs) {
                try {
                    bestHit.setTaxId(tax);
                } catch (IllegalArgumentException e) {
                    // nothing
                }
            }
        }
        return hitsWithBestScore.stream().filter(syn -> syn.getTaxId() != null).collect(Collectors.toList());
    }

    @Override
    public SemanticDisambiguation getSemanticDisambiguation() {
        return null;
    }

    @Override
    public CandidateRetrieval getCandidateRetrieval() {
        return candidateRetrieval;
    }

    @Override
    public TermNormalizer getTermNormalizer() {
        return normalizer;
    }

    @Override
    public DocumentMappingResult map(GeneDocument document) throws GeneMappingException {

        for (GeneMention gm : document.getGenesIterable()) {
            gm.setNormalizer(normalizer);
            map(gm);
        }
        return disambiguation.disambiguateDocument(new WeepingTreeDocumentDisambiguationData(document, filterTaxIds));
    }
}
