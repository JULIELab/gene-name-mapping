package de.julielab.jules.ae.genemapping.genemodel;

import de.julielab.java.utilities.spanutils.SpanImplBase;
import org.apache.commons.lang3.Range;

public class PosTag extends SpanImplBase {
	private String tag;

	public PosTag(String tag, Range<Integer> offsets) {
		super(offsets);
		this.tag = tag;
	}

	public String getTag() {
		return tag;
	}

	public void setTag(String tag) {
		this.tag = tag;
	}

	@Override
	public String toString() {
		return "PosTag [tag=" + tag + ", offsets=" + offsets + "]";
	}

}
