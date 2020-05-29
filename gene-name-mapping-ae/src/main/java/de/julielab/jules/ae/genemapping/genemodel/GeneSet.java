package de.julielab.jules.ae.genemapping.genemodel;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import cc.mallet.types.FeatureVector;
import cc.mallet.types.Instance;
import de.julielab.jules.ae.genemapping.MentionMappingResult;
import de.julielab.jules.ae.genemapping.SynHit;
import de.julielab.jules.ae.genemapping.genemodel.GeneMention.SpecificType;

public class GeneSet extends HashSet<GeneMention> {

    /**
     *
     */
    private static final long serialVersionUID = -4038206150665551536L;
    private List<SynHit> setId;
    private FeatureVector featureVector;
    private SpecificType specificType;
    private boolean isPlural;
    private Instance instance;
    private double specificTypeConfidence;

    public GeneSet(HashSet<GeneMention> genes, List<SynHit> setId) {
        super(genes);
        this.setId = setId;
    }

    public GeneSet() {
        super();
        this.setId = MentionMappingResult.REJECTION;
    }

    public Instance getInstance() {
        return instance;
    }

    public void setInstance(Instance instance) {
        this.instance = instance;

    }

    /**
     * The set ID represent the gene ID that all elements in the set belong to
     *
     * @return The ID of the elements in this set.
     */
    public List<SynHit> getSetId() {
        return setId;
    }

    public void setSetId(List<SynHit> setId) {
        this.setId = setId;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        int thisId = System.identityHashCode(this);
        int objId = System.identityHashCode(obj);
        return thisId == objId;
    }

    /**
     * Returns the text of any gene mention in this set or null, if the set is
     * empty.
     *
     * @return Any gene mention text of this set.
     */
    public String getRepresentationText() {
        Optional<GeneMention> any = stream().findAny();
        if (any.isPresent())
            return any.get().getText();
        return null;
    }

    public FeatureVector getFeatureVector() {
        return featureVector;
    }

    public void setFeatureVector(FeatureVector featureVector) {
        this.featureVector = featureVector;
        stream().forEach(gm -> gm.setFeatureVector(featureVector));
    }

    public SpecificType getSpecificType() {
        return specificType;
    }

    public void setSpecificType(SpecificType specificType) {
        this.specificType = specificType;
        stream().forEach(gm -> gm.setSpecificType(specificType));
    }

    public boolean isPlural() {
        return isPlural;
    }

    public void setPlural(boolean isPlural) {
        this.isPlural = isPlural;
    }

    public double getSpecificTypeConfidence() {
        return specificTypeConfidence;
    }

    public void setSpecificTypeConfidence(double specificTypeConfidence) {
        this.specificTypeConfidence = specificTypeConfidence;
        stream().forEach(gm -> gm.setSpecificTypeConfidence(specificTypeConfidence));
    }
}
