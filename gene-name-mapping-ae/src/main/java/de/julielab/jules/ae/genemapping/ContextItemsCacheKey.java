package de.julielab.jules.ae.genemapping;

import java.util.Objects;

public class ContextItemsCacheKey {
    private String geneId;
    private String indexField;

    public ContextItemsCacheKey(String geneId, String indexField) {
        this.geneId = geneId;
        this.indexField = indexField;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContextItemsCacheKey that = (ContextItemsCacheKey) o;
        return Objects.equals(geneId, that.geneId) &&
                Objects.equals(indexField, that.indexField);
    }

    @Override
    public int hashCode() {

        return Objects.hash(geneId, indexField);
    }

    public String getGeneId() {
        return geneId;
    }

    public void setGeneId(String geneId) {
        this.geneId = geneId;
    }

    public String getIndexField() {
        return indexField;
    }

    public void setIndexField(String indexField) {
        this.indexField = indexField;
    }
}
