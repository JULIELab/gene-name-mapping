package de.julielab.jules.ae.genemapping;

import de.julielab.jules.ae.genemapping.genemodel.GeneName;

public class CandidateCacheKey {
    public GeneName geneName;
    public String taxId;

    /**
     * Gets gene candidates based only on the name making no restrictions on
     * species.
     *
     * @param geneName
     */
    public CandidateCacheKey(GeneName geneName) {
        this(geneName, null);
    }

    /**
     * Gets gene candidates accordingi to <tt>geneName</tt> but restricted
     * to species with taxonomy ID <tt>taxId</tt>.
     *
     * @param geneName
     * @param taxId
     */
    public CandidateCacheKey(GeneName geneName, String taxId) {
        this.geneName = geneName;
        this.taxId = taxId;
    }

    @Override
    public String toString() {
        return "CandidateCacheKey [geneName=" + geneName + ", taxId=" + taxId + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((geneName == null) ? 0 : geneName.hashCode());
        result = prime * result + ((taxId == null) ? 0 : taxId.hashCode());
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
        CandidateCacheKey other = (CandidateCacheKey) obj;
        if (geneName == null) {
            if (other.geneName != null)
                return false;
        } else if (!geneName.equals(other.geneName))
            return false;
        if (taxId == null) {
            if (other.taxId != null)
                return false;
        } else if (!taxId.equals(other.taxId))
            return false;
        return true;
    }

}
