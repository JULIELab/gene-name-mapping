package de.julielab.jules.ae.genemapping.genemodel;

import de.julielab.jules.ae.genemapping.SynHit;

import java.util.ArrayList;
import java.util.List;

public class GeneIdCandidate {
    private String geneId;
    private String taxId;
    private Number synonymPriority;
    private SynHit synonymHit;
    private GeneMention geneMention;
    private List<MeshHeading> meshHits;

    public GeneIdCandidate(String geneId, String taxId, Number synonymPriority, SynHit synonymHit, GeneMention geneMention) {
        this.geneId = geneId;
        this.taxId = taxId;
        this.synonymPriority = synonymPriority;
        this.synonymHit = synonymHit;
        this.geneMention = geneMention;
    }

    public void addMeshHit(MeshHeading heading) {
        if (meshHits == null)
            meshHits = new ArrayList<>();
        meshHits.add(heading);
    }

    public List<MeshHeading> getMeshHits() {
        return meshHits;
    }

    public void setMeshHits(List<MeshHeading> meshHits) {
        this.meshHits = meshHits;
    }

    public Number getSynonymPriority() {
        return synonymPriority;
    }

    public void setSynonymPriority(Number synonymPriority) {
        this.synonymPriority = synonymPriority;
    }

    public String getGeneId() {
        return geneId;
    }

    public void setGeneId(String geneId) {
        this.geneId = geneId;
    }

    public String getTaxId() {
        return taxId;
    }

    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public SynHit getSynonymHit() {
        return synonymHit;
    }

    public void setSynonymHit(SynHit synonymHit) {
        this.synonymHit = synonymHit;
    }

    public GeneMention getGeneMention() {
        return geneMention;
    }

    public void setGeneMention(GeneMention geneMention) {
        this.geneMention = geneMention;
    }

    @Override
    public String toString() {
        return "GeneIdCandidate{" +
                "geneId='" + geneId + '\'' +
                ", taxId='" + taxId + '\'' +
                ", synonymPriority=" + synonymPriority + ", speciesMentionScore: " + getSynonymHit().getSpeciesMentionScore(taxId) +
                '}';
    }
}