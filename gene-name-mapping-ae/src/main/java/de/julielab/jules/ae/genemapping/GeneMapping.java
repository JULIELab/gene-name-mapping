/**
 * GeneMapper.java
 *
 * Copyright (c) 2008, JULIE Lab.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 *
 * Author: tomanek, jwermter
 *
 * Current version: 1.0
 * Since version:   1.0
 *
 * Creation date: 21.03.2007
 **/

package de.julielab.jules.ae.genemapping;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.julielab.jules.ae.genemapping.mappingcores.MappingCore;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.search.BooleanQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.julielab.jules.ae.genemapping.genemodel.GeneDocument;
import de.julielab.jules.ae.genemapping.genemodel.GeneMention;
import de.julielab.jules.ae.genemapping.utils.GeneCandidateRetrievalException;
import de.julielab.jules.ae.genemapping.utils.GeneMappingException;
import de.julielab.jules.ae.genemapping.utils.SynHitUtils;
import de.julielab.jules.ae.genemapping.utils.norm.TermNormalizer;

public class GeneMapping {

	public static final boolean LEGACY_INDEX_SUPPORT = true;

	public static final String SOURCE_DEFINITION = "UniProt ID (any organism)";
	private static final Logger LOGGER = LoggerFactory.getLogger(GeneMapping.class);
	/*
	 * definitions of the scorer type
	 */
	public static final int SIMPLE_SCORER = 0;
	public static final int TOKEN_JAROWINKLER_SCORER = 1;
	public static final int MAXENT_SCORER = 2;
	public static final int JAROWINKLER_SCORER = 3;
	public static final int LEVENSHTEIN_SCORER = 4;
	/**
	 * This was a test using SecondString TFIDF for scoring but wasn't used eventually. Lucene is very good.
	 * @deprecated
	 */
	public static final int TFIDF = 5;
	public static final int LUCENE_SCORER = 10;
	@Deprecated
	public static final String MAPPING_CORE = "mapping_core";

	private MappingCore mappingCore;

	private GeneMappingConfiguration config;

	private static CandidateFilter candidateFilter;

	/**
	 * Main constructor for the GeneMapper reading especially properties
	 * information.
	 * 
	 * @param propertiesFile
	 * @throws IOException
	 * @throws GeneMappingException
	 * @throws CorruptIndexException
	 */
	public GeneMapping(File propertiesFile) throws IOException, GeneMappingException {
        this(loadConfigurationFile(propertiesFile));
    }

    public GeneMapping(GeneMappingConfiguration configuration) throws IOException, GeneMappingException {
        this.config = configuration;
	    // read in configuration settings
		readProperties(configuration);
		candidateFilter = new CandidateFilter();
    }

    private static GeneMappingConfiguration loadConfigurationFile(File propertiesFile) throws IOException {
        // load properties file
        GeneMappingConfiguration config = new GeneMappingConfiguration();
        try {
            config.load(new FileInputStream(propertiesFile));
        } catch (FileNotFoundException e) {
            LOGGER.error("specified properties file does not exist!");
            throw e;
        }
        return config;
    }

	private void readProperties(GeneMappingConfiguration config) throws GeneMappingException {
		String mappingCore = config.getProperty(GeneMappingConfiguration.MAPPING_CORE);
		if (mappingCore == null)
			throw new GeneMappingException(
					"Property mapping_core is undefined. The mapping core to be used must be specified.");
		try {
			this.mappingCore = (MappingCore) Class.forName(mappingCore)
					.getDeclaredConstructor(GeneMappingConfiguration.class).newInstance(config);
		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException
				| NoSuchMethodException | SecurityException | ClassNotFoundException e) {
			throw new GeneMappingException(e);
		}

	}

	/*
	 * mapping functions
	 */
	/**
	 * This mapping returns a list of SynHits. No semantic disambiguation is
	 * done here. TopN hits with the highest (lucene) scores are returned. Not
	 * needed for actual mapping but used for generating training material for
	 * MaxEntScorer.
	 * 
	 * @param searchTerm
	 *            the term to be mapped
	 * @param topN
	 *            number of hits to be returned
	 * @throws GeneCandidateRetrievalException 
	 */
	public ArrayList<SynHit> mapTopN(String searchTerm, int topN) throws IOException, GeneCandidateRetrievalException {
		TermNormalizer normalizer = new TermNormalizer();
		// 1. normalize search term
		String normalizedSearchTerm = normalizer.normalize(searchTerm);
		normalizedSearchTerm = removeModifiers(normalizedSearchTerm);
		if (normalizedSearchTerm.equals(""))
			return null;
		LOGGER.info("map() - searching for term: " + searchTerm + " as > " + normalizedSearchTerm + " <");
		// 1. get the candidates from the dictionary and score them
		List<SynHit> allHits = mappingCore.getCandidateRetrieval().getCandidates(normalizedSearchTerm);
		ArrayList<SynHit> topNHits = new ArrayList<SynHit>();
		for (int i = 0; i < Math.min(allHits.size(), topN); i++) {
			SynHit synHit = allHits.get(i);
			if (!candidateFilter.filterOut(normalizedSearchTerm, synHit.getSynonym())) {
				topNHits.add(allHits.get(i));
			}
		}
		LOGGER.info("topN mapping found > " + topNHits.size() + " <  for candidate '" + searchTerm + "': "
				+ SynHitUtils.showHitIDs(topNHits));
		return topNHits;
	}

	/**
	 * A wrapper to the main mapping function. This one does not require an
	 * organism to be specified and does thus completely organism-agnostic
	 * search (currently used basically for backward compatibility to BC
	 * evaluation).
	 * 
	 * @param searchTerm
	 * @param contextQuery
	 * @return the SynHits that apply to the given searchTerm
	 * @throws Exception
	 */
	public List<SynHit> map(String searchTerm, BooleanQuery contextQuery) throws GeneMappingException {
		GeneMention predictedMention = new GeneMention(searchTerm);
		MentionMappingResult map = map(predictedMention, contextQuery, null);
		return map.resultEntries;
	}

	/**
	 * Actual mapping method. This mapping functions has semantic disambiguation
	 * as well. First it checks for general, organism-specific hits
	 * (getCandidates). If organisms is given (i.e. is not null or not empty)
	 * semantic disambiguation is performed with this organism list.
	 * 
	 * @param searchTerm
	 *            the term to do the mapping for
	 * @param contextQuery
	 *            the term's context (i.e. the document/abstract where it was
	 *            found in)
	 * @param documentContext
	 * @return ArrayList with SynHits
	 * @throws Exception
	 */
	public MentionMappingResult map(GeneMention searchTerm, BooleanQuery contextQuery, String documentContext)
			throws GeneMappingException {
		return mappingCore.map(searchTerm);
	}

	public DocumentMappingResult map(GeneDocument document) throws GeneMappingException {
		return mappingCore.map(document);
	}

	/**
	 * @param normalizedSearchTerm
	 * @return the normalizedSearchTerm with all modifiers removed
	 */
	public static String removeModifiers(String normalizedSearchTerm) {
		Pattern p;
		Matcher m;
		LOGGER.debug("TRYING to remove modifiers or even complete term: " + normalizedSearchTerm);
		p = candidateFilter.patternUnspecifieds;
		m = p.matcher(normalizedSearchTerm);
		if (m.matches()) {
			LOGGER.debug("IS UNSPECIFIED: " + normalizedSearchTerm);
			normalizedSearchTerm = normalizedSearchTerm.replaceFirst(candidateFilter.UNSPECIFIEDS, "");
			LOGGER.debug("UNSPECIFIED REMOVED: |" + normalizedSearchTerm + "|");
		}
		normalizedSearchTerm = normalizedSearchTerm.trim();

		p = candidateFilter.patternDomainFamilies;
		m = p.matcher(normalizedSearchTerm);
		if (m.matches()) {
			LOGGER.debug("IS DOMAIN: " + normalizedSearchTerm);
			normalizedSearchTerm = normalizedSearchTerm.replaceFirst(candidateFilter.DOMAIN_FAMILIES, "");
			LOGGER.debug("DOMAIN REMOVED: |" + normalizedSearchTerm + "|");
		}
		normalizedSearchTerm = normalizedSearchTerm.trim();

		p = candidateFilter.patternPreMods;
		m = p.matcher(normalizedSearchTerm);
		if (m.matches()) {
			LOGGER.debug("PREMODIFIER: " + normalizedSearchTerm);
			normalizedSearchTerm = normalizedSearchTerm.replaceFirst(candidateFilter.PREMODS, "");
		}
		normalizedSearchTerm = normalizedSearchTerm.trim();

		p = candidateFilter.patternNonDesc;
		m = p.matcher(normalizedSearchTerm);
		if (m.matches()) {
			LOGGER.debug("IS NONDESC: " + normalizedSearchTerm);
			normalizedSearchTerm = normalizedSearchTerm.replaceFirst(candidateFilter.NON_DESC, "");
			LOGGER.debug("NONDESC REMOVED: |" + normalizedSearchTerm + "|");
		}
		normalizedSearchTerm = normalizedSearchTerm.trim();

		p = null;
		m = null;

		return normalizedSearchTerm.trim();
	}
	
	public static String removeUnspecifieds(String normalizedSearchTerm) {
		Pattern p = candidateFilter.patternUnspecifieds;
		Matcher m = p.matcher(normalizedSearchTerm);
		LOGGER.debug("TRYING to remove modifiers or even complete term: " + normalizedSearchTerm);

		if (m.matches()) {
			LOGGER.debug("IS UNSPECIFIED: " + normalizedSearchTerm);
			normalizedSearchTerm = normalizedSearchTerm.replaceFirst(candidateFilter.UNSPECIFIEDS, "");
			LOGGER.debug("UNSPECIFIED REMOVED: |" + normalizedSearchTerm + "|");
		}
		return normalizedSearchTerm.trim();
	}
	
	public static String removeNondescriptives(String normalizedSearchTerm) {
		Pattern p = candidateFilter.patternNonDesc;
		Matcher m = p.matcher(normalizedSearchTerm);
		
		if (m.find()) {
			LOGGER.debug("IS NONDESC: " + normalizedSearchTerm);
			normalizedSearchTerm = normalizedSearchTerm.replaceFirst(candidateFilter.NON_DESC, "");
			LOGGER.debug("NONDESC REMOVED: |" + normalizedSearchTerm + "|");
		}
		return normalizedSearchTerm.trim();

	}
	
	public static String removeDomainFamilies(String normalizedSearchTerm) {
		Pattern p = candidateFilter.patternDomainFamilies;
		Matcher m = p.matcher(normalizedSearchTerm);
		if (m.matches()) {
			LOGGER.debug("IS DOMAIN: " + normalizedSearchTerm);
			normalizedSearchTerm = normalizedSearchTerm.replaceFirst(candidateFilter.DOMAIN_FAMILIES, "");
			LOGGER.debug("DOMAIN REMOVED: |" + normalizedSearchTerm + "|");
		}
		return normalizedSearchTerm.trim();
	}
	
	public static String removePremodifiers(String normalizedSearchTerm) {
		Pattern p = candidateFilter.patternPreMods;
		Matcher m = p.matcher(normalizedSearchTerm);
		if (m.matches()) {
			LOGGER.debug("PREMODIFIER: " + normalizedSearchTerm);
			normalizedSearchTerm = normalizedSearchTerm.replaceFirst(candidateFilter.PREMODS, "");
		}
		return normalizedSearchTerm.trim();
	}

	public MappingCore getMappingCore() {
		return mappingCore;
	}

	public void setMappingCore(MappingCore mappingCore) {
		this.mappingCore = mappingCore;
	}

	/**
	 * Convenience method mostly used for tests. The <tt>term</tt> will be
	 * wrapped into a {@link GeneMention}. However, no offset information or
	 * other data about the original gene mention will be known, of course.
	 * 
	 * @param term
	 * @param contextQuery
	 * @param documentContext
	 * @return
	 * @throws Exception
	 */
	public MentionMappingResult map(String term, BooleanQuery contextQuery, String documentContext) throws GeneMappingException {
		return map(new GeneMention(term), contextQuery, documentContext);
	}

	public GeneMappingConfiguration getConfiguration() {
		return config;
	}
}
