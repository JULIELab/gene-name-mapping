package de.julielab.jules.ae.genemapping.genemodel;

import de.julielab.java.utilities.spanutils.OffsetMap;
import org.apache.commons.lang3.Range;

import java.util.Set;

public class SpeciesCandidates {

	private OffsetMap<SpeciesMention> titleCandidates;
	private Set<String> meshCandidates;
	private OffsetMap<SpeciesMention> textCandidates;

	public SpeciesCandidates(OffsetMap<SpeciesMention> titleCandidates, Set<String> meshCandidates,
			OffsetMap<SpeciesMention> textCandidates) {
		this.titleCandidates = titleCandidates;
		this.meshCandidates = meshCandidates;
		this.textCandidates = textCandidates;
	}

	/**
	 * If the <tt>textCandidates</tt> include species mentions for the title,
	 * this constructor will automatically derive the title species IDs.
	 * 
	 * @param titleBegin
	 * @param titleEnd
	 * @param meshCandidates
	 * @param textCandidates
	 */
	public SpeciesCandidates(int titleBegin, int titleEnd, Set<String> meshCandidates,
			OffsetMap<SpeciesMention> textCandidates) {
		this.textCandidates = textCandidates;
		if (null == textCandidates)
			this.textCandidates = OffsetMap.emptyOffsetMap();
		this.titleCandidates = new OffsetMap(this.textCandidates.restrictTo(Range.between(titleBegin, titleEnd)));
		this.meshCandidates = meshCandidates;
	}

	public OffsetMap<SpeciesMention> getTitleCandidates() {
		return titleCandidates;
	}

	public void setTitleCandidates(OffsetMap<SpeciesMention> titleCandidates) {
		this.titleCandidates = titleCandidates;
	}

	public Set<String> getMeshCandidates() {
		return meshCandidates;
	}

	public void setMeshCandidates(Set<String> meshCandidates) {
		this.meshCandidates = meshCandidates;
	}

	public OffsetMap<SpeciesMention> getTextCandidates() {
		return textCandidates;
	}

	public void setTextCandidates(OffsetMap<SpeciesMention> textCandidates) {
		this.textCandidates = textCandidates;
	}
}
