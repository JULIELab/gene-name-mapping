package de.julielab.jules.ae.genemapping.genemodel;

import de.julielab.jules.ae.genemapping.SynHit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MeshHeading {
    private String heading;
    private List<SynHit> geneSynonymHits;
    private List<String> taxonomyIds;

    public MeshHeading(String heading) {
        this.heading = heading;
    }

    public String getHeading() {
        return heading;
    }

    public void setHeading(String heading) {
        this.heading = heading;
    }

    public List<SynHit> getGeneSynonymHits() {
        return geneSynonymHits;
    }

    public void setGeneSynonymHits(List<SynHit> geneSynonymHits) {
        this.geneSynonymHits = geneSynonymHits;
    }

    public List<String> getTaxonomyIds() {
        return taxonomyIds == null ? Collections.emptyList() : taxonomyIds;
    }

    public void setTaxonomyIds(List<String> taxonomyIds) {
        this.taxonomyIds = taxonomyIds;
    }

    public void addTaxonomyId(String taxId) {
        if (taxonomyIds == null)
            taxonomyIds = new ArrayList<>();
        taxonomyIds.add(taxId);
    }

    @Override
    public String toString() {
        return "MeshHeading{" +
                "heading='" + heading + '\'' +
                ", geneSynonymHits=" + geneSynonymHits +
                ", taxonomyIds=" + taxonomyIds +
                '}';
    }
}
