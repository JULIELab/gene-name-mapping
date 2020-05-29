package de.julielab.jules.ae.genemapping.disambig;

import de.julielab.jules.ae.genemapping.genemodel.GeneDocument;

import java.util.Set;

public class WeepingTreeDocumentDisambiguationData implements DocumentDisambiguationData {
    private GeneDocument document;
    private Set<String> taxonomyIds;

    /**
     *
     * @param document The document with the genes to disambiguate.
     * @param taxonomyIds The taxonomy IDs to restrict the mapping to.
     */
    public WeepingTreeDocumentDisambiguationData(GeneDocument document, Set<String> taxonomyIds) {
        this.document = document;
        this.taxonomyIds = taxonomyIds;
    }

    public Set<String> getTaxonomyIds() {
        return taxonomyIds;
    }

    @Override
    public GeneDocument getDocument() {
        return document;
    }
}
