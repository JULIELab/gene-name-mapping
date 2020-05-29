/** 
 * IndexGenerator.java
 * 
 * Copyright (c) 2006, JULIE Lab. 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 *
 * Author: tomanek
 * 
 * Current version: 1.5.1
 * Since version:   1.0
 *
 * Creation date: Nov 30, 2006 
 * 
 * This class generates the Lucene index from the modified biothesaurus as
 * provided by EBI in BootSTREP.
 * 
 * This version of the index generator expects a consolidated biothesaurus file
 * which only consists of these columns:
 * - col1: synonym (normalized)
 * - col2: uniref_50
 * 
 * IMPORTANT NOTES:
 * - no normalization is done here, so better do the normalization of the BT yourself
 * - for better performance: make entries in bt file unique!
 * 
 **/

package de.julielab.jules.ae.genemapping.resources;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.julielab.jules.ae.genemapping.CandidateFilter;
import de.julielab.jules.ae.genemapping.index.SynonymIndexFieldNames;
import de.julielab.jules.ae.genemapping.utils.norm.TermNormalizer;

public class SynonymIndexGenerator {

	private static final Logger log = LoggerFactory.getLogger(SynonymIndexGenerator.class);

	/**
	 * The synonym index is filtered for unspecifieds and others. This field
	 * determines whether filtered items should be omitted completely from the index
	 * or if they should just be flagged to be filtered but included into the index.
	 * The latter will lead to a larger index, of course. Used for experiments, not
	 * required as of January 30, 2018.
	 */
	private static final Boolean OMIT_FILTERED = true;

	/*
	 * defines the maximum length of synonyms to be considered longer synonyms are
	 * omitted, i.e. not stored in the index
	 */
	private static final int MAX_SYNLENGTH = 8;
	private static final int MIN_SYNLENGTH = 2;

	/**
	 * A file containing gene or protein names / synonyms and their respective NCBI
	 * Gene or UniProt ID. No term normalization is expected for this dictionary.
	 */
	private File dictFile;

	Map<String, String> id2tax;

	Directory indexDirectory;

	private static final boolean debug = false;

	/**
	 * To execute the ContextIndexGenerator start it with the following command-line
	 * arguments:<br>
	 * arg0: path to resources directory arg1: path to synonym indices directory
	 * 
	 * @param args
	 */
	public static void main(String[] args) {

		long s1 = System.currentTimeMillis();

		if (args.length != 3) {
			System.err.println(
					"Usage: SynonymIndexGenerator <resourcesDirectory> <gene_info file name> <geneSynonymIndicesDirectory>");
			System.exit(1);
		}

		String resPath = args[0];
		File resDir = new File(resPath);
		if (!resDir.isDirectory()) {
			System.err.println("Could not find resources directory");
			System.exit(1);
		}
		if (!resPath.endsWith(File.separator)) {
			resPath = resPath + File.separator;
		}

		File geneInfo = new File(resPath + args[1]);
		if (!geneInfo.exists()) {
			System.err.println("Gene info file could not be found at " + geneInfo.getAbsolutePath());
			System.exit(1);
		}

		String indexPath = args[2];
		if (!indexPath.endsWith("/")) {
			indexPath = indexPath + "/";
		}
		File geneIndexDir = new File(indexPath + "geneSynonymIndex");
		File proteinIndexDir = new File(indexPath + "proteinSynonymIndex");

		File upDictFile = new File(resPath + "gene.dict.up");
		checkFile(upDictFile);

		File egDictFile = new File(resPath + "gene.dict.eg");
		checkFile(egDictFile);

		File upTaxMap = new File(resPath + "up2eg2tax.map");
		checkFile(upTaxMap);

		File egTaxMap = geneInfo;

		SynonymIndexGenerator indexGenerator;
		try {
			// indexGenerator = new SynonymIndexGenerator(upDictFile, proteinIndexDir);
			// indexGenerator.readUpTaxMap(upTaxMap);
			// indexGenerator.createIndex();
			indexGenerator = new SynonymIndexGenerator(egDictFile, geneIndexDir);
			indexGenerator.readEgTaxMap(egTaxMap);
			indexGenerator.createIndex();
		} catch (IOException e) {
			e.printStackTrace();
		}

		long s2 = System.currentTimeMillis();
		System.out.println("Index created successfully! (" + (s2 - s1) / 1000 + " sec)");
	}

	private static void checkFile(File file) {
		if (!file.isFile())
			throw new IllegalArgumentException("File \"" + file.getAbsolutePath() + "\" could not be found.");
	}

	/**
	 * 
	 * @param dictFile
	 *            A file containing gene or protein names / synonyms and their
	 *            respective NCBI Gene or UniProt ID. No term normalization is
	 *            expected for this dictionary.
	 * @param indexFile
	 *            The directory where the name / synonym index will be written to.
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	public SynonymIndexGenerator(File dictFile, File indexFile) throws FileNotFoundException, IOException {
		System.out.println("Building synonym index from dictionary " + dictFile.getAbsolutePath());
		this.dictFile = dictFile;
		indexDirectory = createIndexDirectory(indexFile);

	}

	/**
	 * create the index, i.e. read from the biothesaurus file (which is expected to
	 * have normalized synonyms!) and then write it to the index.
	 * 
	 * @throws IOException
	 */
	public void createIndex() throws IOException {

		CandidateFilter cf = new CandidateFilter();

		WhitespaceAnalyzer wsAnalyzer = new WhitespaceAnalyzer();
		IndexWriterConfig iwc = new IndexWriterConfig(wsAnalyzer);
		iwc.setOpenMode(OpenMode.CREATE);
		IndexWriter iw = new IndexWriter(indexDirectory, iwc);

		TermNormalizer normalizer = new TermNormalizer();
		int counter = 0;
		BufferedReader normDictReader = new BufferedReader(new FileReader(dictFile));

		System.out.println(
				"Generating index now. This may take quite a while (up to several hours when input files are large) ...");
		// now loop through dictionary and add entries to the index
		try {

			String line = "";
			while ((line = normDictReader.readLine()) != null) {

				String[] values = line.split("\t");

				// check whether format is OK
				if (values.length != 3) {
					System.err.println("ERR: normalized dictionary not in expected format. \ncritical line: " + line);
					// System.exit(-1);
					continue;
				}

				// now get the field values
				String name = values[0];
				String normalizedName = normalizer.normalize(name);
				List<String> normalizedNameVariant = normalizer.generateVariants(name).stream()
						.map(normalizer::normalize).collect(Collectors.toList());
				String id = values[1];
				Integer priority = Integer.parseInt(values[2]);

				boolean filtered = false;

				// ignore synonyms smaller than MIN_SYNLENGTH or longer than
				// MAX_SYNLENGTH
				int synTokenNum = normalizedName.split(" ").length;
				if (synTokenNum > MAX_SYNLENGTH
						|| (synTokenNum < MIN_SYNLENGTH && normalizedName.length() < MIN_SYNLENGTH)) {
					log.debug("Removed due to illegal length (too short or too long): {}", normalizedName);
					continue;
				}

				// ignore syns that look like domain or family names
				Pattern p = cf.patternDomainFamilies;
				Matcher m = p.matcher(normalizedName);
				if (m.matches()) {
					log.debug("DOMAIN/FAMILY REMOVED: |{}|", normalizedName);
					filtered = true;
				}

				p = cf.patternUnspecifieds;
				m = p.matcher(normalizedName);
				if (m.matches()) {
					log.debug("UNSPECIFIED REMOVED: |{}|", normalizedName);
					filtered = true;
				}

				if (filtered && OMIT_FILTERED)
					continue;

				showDebug(id + "\t" + normalizedName);

				String tax = "";
				if (id2tax.get(id) != null) {
					tax = id2tax.get(id);
				}

				// make fields
				List<Field> fields = new ArrayList<>();
				Field idField = new StringField(SynonymIndexFieldNames.ID_FIELD, id, Store.YES);
				Field originalNameField = new TextField(SynonymIndexFieldNames.ORIGINAL_NAME, name.toLowerCase(),
						Store.YES);
				Field lookupSynField = new TextField(SynonymIndexFieldNames.LOOKUP_SYN_FIELD, normalizedName,
						Store.YES);
				Field taxField = new StringField(SynonymIndexFieldNames.TAX_ID_FIELD, tax, Field.Store.YES);
				IntPoint priorityField = new IntPoint(SynonymIndexFieldNames.PRIORITY, priority);
				StoredField storedPriorityField = new StoredField(SynonymIndexFieldNames.PRIORITY, priority);
				if (!OMIT_FILTERED) {
					IntPoint filteredField = new IntPoint(SynonymIndexFieldNames.FILTERED, filtered ? 1 : 0);
					StoredField storedFilteredField = new StoredField(SynonymIndexFieldNames.FILTERED,
							filtered ? 1 : 0);
					fields.add(filteredField);
					fields.add(storedFilteredField);
				}
				fields.add(idField);
				fields.add(originalNameField);
				fields.add(lookupSynField);
				fields.add(taxField);
				fields.add(priorityField);
				fields.add(storedPriorityField);
				for (int i = 0; i < normalizedNameVariant.size(); ++i)
					fields.add(new TextField(SynonymIndexFieldNames.VARIANT_NAME, normalizedNameVariant.get(i),
							Store.YES));
				for (int i = 0; i < normalizedNameVariant.size(); ++i)
					fields.add(new TextField(SynonymIndexFieldNames.STEMMED_NORMALIZED_NAME,
							normalizedNameVariant.get(i), Store.YES));

				// make document and add to synonym index
				Document d = new Document();
				for (Field f : fields)
					d.add(f);
				iw.addDocument(d);

				++counter;
				if (counter % 10000 == 0) {
					System.err.println("# entries processed: " + counter);
				}
			}

			iw.close();

			normDictReader.close();

		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/**
	 * create the directory object where to put the lucene index...
	 */
	private FSDirectory createIndexDirectory(File indexFile) {
		FSDirectory fdir = null;
		try {
			fdir = FSDirectory.open(indexFile.toPath());
		} catch (IOException e) {
			e.printStackTrace();
		}
		return fdir;
	}

	private void showDebug(String s) {
		if (debug) {
			System.out.println(s);
		}
	}

	private void readUpTaxMap(File taxMap) throws IOException {
		System.out.println("Reading up2eg2tax.map ...");
		id2tax = new HashMap<String, String>();

		BufferedReader reader = new BufferedReader(new FileReader(taxMap));
		String line = "";

		while ((line = reader.readLine()) != null) {
			String[] entry = line.split("\t");

			if (entry.length != 3) {
				System.err.println("ERR: up2eg2tax.map not in expected format. \ncritical line: " + line);
				System.exit(-1);
			}

			String id = entry[0].trim();
			String taxId = entry[2].trim();
			id2tax.put(id, taxId);
		}

		reader.close();
	}

	private void readEgTaxMap(File geneInfo) throws IOException {
		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(new GZIPInputStream(new FileInputStream(geneInfo))))) {
			id2tax = br.lines().collect(
					Collectors.<String, String, String>toMap(l -> l.split("\\t", 3)[1], l -> l.split("\\t", 3)[0]));
		}
	}

}
