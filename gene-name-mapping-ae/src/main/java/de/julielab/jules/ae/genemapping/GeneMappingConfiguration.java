package de.julielab.jules.ae.genemapping;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import de.julielab.java.utilities.FileUtilities;

public class GeneMappingConfiguration extends Properties {
    public static final String CONTEXT_ITEMS_INDEX = "context_items_index";
	public static final String SYNONYM_INDEX = "mention_index";
	public static final String CONTEXT_INDEX = "semantic_index";
    /**
	 *
	 */
	private static final long serialVersionUID = -1636778667307698253L;

	public static final String DEFAULT_SPECIES = "default_species";

	public static final String MAPPING_CORE = "mapping_core";
	public static final String EXACT_SCORER_TYPE = "exact_scorer_type";
	public static final String APPROX_SCORER_TYPE = "approx_scorer_type";
	public static final String SPELLING_INDEX = "spelling_index";
	public static final String MENTION_INDEX = "mention_index";
	public GeneMappingConfiguration() {
	}
	
	public GeneMappingConfiguration(File configurationFile) throws IOException {
		this.load(FileUtilities.getInputStreamFromFile(configurationFile));
	}

}
