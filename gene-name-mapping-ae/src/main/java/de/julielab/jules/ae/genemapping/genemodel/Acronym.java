package de.julielab.jules.ae.genemapping.genemodel;

import de.julielab.java.utilities.spanutils.Span;
import org.apache.commons.lang3.Range;

public class Acronym implements Span {
	private String acronym;
	private Range<Integer> offsets;
	private AcronymLongform longform;

	public Acronym(String acronym, int begin, int end, AcronymLongform longform) {
		this.acronym = acronym;
		this.offsets = Range.between(begin, end);
		this.longform = longform;
	}

	public Acronym() {
	}

	public String getAcronym() {
		return acronym;
	}

	public void setAcronym(String acronym) {
		this.acronym = acronym;
	}

	public Range<Integer> getOffsets() {
		return offsets;
	}

	@Override
	public String toString() {
		return "Acronym [acronym=" + acronym + ", offsets=" + offsets + ", longform=" + longform.getOffsets() + "]";
	}

	public void setOffsets(Range<Integer> offsets) {
		this.offsets = offsets;
	}

	public AcronymLongform getLongform() {
		return longform;
	}

	public void setLongform(AcronymLongform longform) {
		this.longform = longform;
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
