package de.julielab.jules.ae.genemapping.disambig;

import de.julielab.jules.ae.genemapping.DocumentMappingResult;
import de.julielab.jules.ae.genemapping.GeneMappingConfiguration;
import de.julielab.jules.ae.genemapping.MentionMappingResult;
import de.julielab.jules.ae.genemapping.SynHit;
import de.julielab.jules.ae.genemapping.genemodel.GeneMention;
import de.julielab.jules.ae.genemapping.utils.GeneMappingException;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * <p>Some simple disambiguation steps for ambiguous synonyms, nothing majorly fancy.
 * Prerequisites:
 * <ul>
 * <li>All gene mentions have a <tt>MentionMappingResult</tt></li>
 * <li>The best candidate(s) of the <tt>MentionMappingResult</tt>s apply to one of the given taxonomy IDs</li>
 * </ul>
 * </p>
 */
public class WeepingTreeDisambiguation implements SemanticDisambiguation {

    private final ContextItemsIndex contextItemsIndex;

    public WeepingTreeDisambiguation(GeneMappingConfiguration configuration) throws GeneMappingException {
        contextItemsIndex = new ContextItemsIndex(configuration);
    }

    @Override
    public MentionMappingResult disambiguateMention(MentionDisambiguationData disambiguationData) throws GeneMappingException {
        final GeneMention gm = disambiguationData.getMention();
        final MentionMappingResult mmr = gm.getMentionMappingResult();
        try {
            if (mmr.bestCandidate != null && !mmr.bestCandidate.isEmpty()) {
                // take the synonym with the best mention score
                final SynHit bestSyn = mmr.bestCandidate.get(0);
                if (!bestSyn.isDisambiguated()) {
                    // This tax ID is set because we assume that the WeepingTreeMappingCore already set it
                    final String taxId = bestSyn.getTaxId();
                    final Map<String, Float> refSeqIdScores = contextItemsIndex.getSynonymRefSeqScoresForTaxIds(bestSyn, Collections.singleton(taxId));
                    final Optional<Map.Entry<String, Float>> maxEntryOpt = refSeqIdScores.entrySet().stream().max(Comparator.comparingDouble(e -> e.getValue()));
                    if (maxEntryOpt.isPresent()) {
                        final String bestId = maxEntryOpt.get().getKey();
                        bestSyn.setId(bestId);
                    }
                }
                mmr.resultEntries = Collections.singletonList(bestSyn);
                mmr.matchType = bestSyn.isExactMatch() ? MentionMappingResult.MatchType.EXACT : MentionMappingResult.MatchType.APPROX;
            }
        } catch (IOException e) {
            throw new GeneMappingException(e);
        }
        return mmr;
    }

    @Override
    public DocumentMappingResult disambiguateDocument(DocumentDisambiguationData disambiguationData) throws GeneMappingException {
        final DocumentMappingResult documentMappingResult = new DocumentMappingResult();
        documentMappingResult.docId = disambiguationData.getDocument().getId();
        for (GeneMention gm : disambiguationData.getDocument().getGenesIterable()) {
            disambiguateMention(new WeepingTreeMentionDisambiguationData(gm, ((WeepingTreeDocumentDisambiguationData) disambiguationData).getTaxonomyIds()));
        }
        documentMappingResult.mentionResults = disambiguationData.getDocument().getGenes().map(GeneMention::getMentionMappingResult).collect(Collectors.toList());
        return documentMappingResult;
    }

    @Override
    public SemanticIndex getSemanticIndex() {
        return contextItemsIndex;
    }
}
