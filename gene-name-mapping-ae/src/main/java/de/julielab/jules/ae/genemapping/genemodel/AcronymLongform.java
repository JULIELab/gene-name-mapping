package de.julielab.jules.ae.genemapping.genemodel;

import de.julielab.java.utilities.spanutils.Span;
import org.apache.commons.lang3.Range;

public class AcronymLongform implements Span {
    private String text;
    private Range<Integer> offsets;

    public AcronymLongform(String text, int begin, int end) {
        this.text = text;
        this.offsets = Range.between(begin, end);
    }

    public AcronymLongform() {
    }

    @Override
    public String toString() {
        return "AcronymLongform [text=" + text + ", offsets=" + offsets + "]";
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Range<Integer> getOffsets() {
        return offsets;
    }

    public void setOffsets(Range<Integer> offsets) {
        this.offsets = offsets;
    }

    @Override
    public int getBegin() {
        return offsets.getMinimum();
    }

    @Override
    public int getEnd() {
        return offsets.getMaximum();
    }
}
