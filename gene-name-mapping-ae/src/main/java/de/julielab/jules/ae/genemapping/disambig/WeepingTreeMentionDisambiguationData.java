package de.julielab.jules.ae.genemapping.disambig;

import de.julielab.jules.ae.genemapping.genemodel.GeneMention;

import java.util.Set;

public class WeepingTreeMentionDisambiguationData implements MentionDisambiguationData {
    private GeneMention gm;
    private Set<String> taxonomyIds;

    /**
     * @param gm          The gene mention to disambiguate.
     * @param taxonomyIds The taxonomy IDs to restrict the mapping to.
     */
    public WeepingTreeMentionDisambiguationData(GeneMention gm, Set<String> taxonomyIds) {
        this.gm = gm;
        this.taxonomyIds = taxonomyIds;
    }

    public Set<String> getTaxonomyIds() {
        return taxonomyIds;
    }

    @Override
    public GeneMention getMention() {
        return gm;
    }
}
