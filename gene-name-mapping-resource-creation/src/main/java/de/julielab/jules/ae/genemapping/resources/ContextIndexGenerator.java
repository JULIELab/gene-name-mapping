/** 
 * IndexGenerator.java
 * 
 * Copyright (c) 2006, JULIE Lab. 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 *
 * Author: wermter
 * 
 * Current version: 1.5.1
 * Since version:   1.0
 *
 * Creation date: Nov 30, 2006 
 * 
 * This class puts the semantic context for a given gene dictionary into
 * a Lucene index.
 * 
 **/

package de.julielab.jules.ae.genemapping.resources;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import de.julielab.jules.ae.genemapping.index.ContextGenerator;
import de.julielab.jules.ae.genemapping.index.ContextIndexFieldNames;
import de.julielab.jules.ae.genemapping.utils.ContextUtils;

public class ContextIndexGenerator {

	//private static final File BIOTHESAURUS_FILE = new File("/data/data_resources/biology/Julie_BT/uniprot_subset/uniprot.plus_ids.unique");
	//private static final File BIOTHESAURUS_FILE = new File("/home/jwermter/biocreative2_data/real_text_data/entrezGeneLexicon.norm.realtext");

	// StemNet:
//	private static final File BIOTHESAURUS_FILE = new File("/mnt/data_stemnet/resources/dictionaries/gene_dictionaries/stemnet/up_index_resources/uniprot.ids");
	// private static final File BIOTHESAURUS_FILE = new File("/data/data_resources/biology/up_index_resources/uniprot.ids");
	// Update Engelmann:
	// private static final File BIOTHESAURUS_FILE = new File("/home/engelmann/geno/Dictionaries_new/uniprot.ids");

	// BC2 new:
	//private static final File BIOTHESAURUS_FILE = new File("/mnt/data_stemnet/resources/dictionaries/gene_dictionaries/stemnet/eg_index_resources_human/eg.ids");

	// BC2 old:
	//private static final File BIOTHESAURUS_FILE = new File("/home/jwermter/biocreative2_data/entrezGene.ids");

	// StemNet:
	// private static final File INDEX_FILE = new File("data/app_data/gene_context_index");
	// Update Engelmann:
	// private static final File INDEX_FILE = new File("/home/engelmann/geno/Indices/gene_context_index");

	// BC2:
	//private static final File INDEX_FILE = new File("data/eval_data/biocreative2_data/entrezGeneContextToken_index");




	/*
	 * define some fields in the index:
	 * SYN_FIELD: this field is to be searched
	 * ID_FIELD: there the id is stored (organism-independent)
	 * LOOKUP_SYN_FIELD: the synonym is stored again here (needed for calculating the score)
	 */

//	//public static final String ID_FIELD = "uniprot_id";
//	//public static final String SYN_FIELD = "synonym";
//	public static final String LOOKUP_ID_FIELD = "indexed_id";
//	//public static final String CONTEXT = "context";
//	public static final String LOOKUP_CONTEXT_FIELD = "indexed_context";
//



	private File biothesaurusFile;
	private String resourcesDir;

	Directory indexDirectory;

	private static final boolean debug = false;
	private static final boolean useContextTypes = false;

	/**
	 * To execute the ContextIndexGenerator start it with the following command-line arguments:<br> 
	 * arg0: path to resources directory
	 * arg1: path to context indices directory
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		
		long s1 = System.currentTimeMillis();
		
		if (args.length != 2) {
			System.err.println("Usage: ContextIndexGenerator <resourcesDirectory> <geneContextIndicesDirectory>");
			System.exit(-1);
		}
		
		String indexPath = args[1];
		if (!indexPath.endsWith("/")) {
			indexPath = indexPath + "/";
		}
		File geneIndexDir = new File(indexPath + "geneContextIndex");
		File proteinIndexDir = new File(indexPath + "proteinContextIndex");
		
		String resPath = args[0];
		File resDir = new File(resPath);
		if (!resDir.isDirectory()) {
			System.err.println("Could not find resources directory");
			System.exit(-1);
		}
		if (!resPath.endsWith("/")) {
			resPath = resPath + "/";
		}
		
		File upFile = new File(resPath + "up.ids");
		if (!upFile.isFile()) {
			System.err.println("Could not find file uniprot.ids");
			System.exit(-1);
		}	
		File egFile = new File(resPath + "eg.ids");
		if (!egFile.isFile()) {
			System.err.println("Could not find file eg.ids");
			System.exit(-1);
		}

		ContextIndexGenerator indexGenerator;
		try {
			indexGenerator = new ContextIndexGenerator(upFile, proteinIndexDir, resPath);
			indexGenerator.createIndex("protein");
			indexGenerator = new ContextIndexGenerator(egFile, geneIndexDir, resPath);
			indexGenerator.createIndex("gene");
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		long s2 = System.currentTimeMillis();
		System.out.println("Indices created successfully! (" + (s2 - s1) / 1000 + " sec)");
	}

	/**
	 * constructor which creates index in the specified directory on the disk
	 */
	public ContextIndexGenerator(File biothesaurusFile,
			File indexFile, String resourcesDir) throws FileNotFoundException, IOException {
		this.biothesaurusFile = biothesaurusFile;
		this.resourcesDir = resourcesDir;
		indexDirectory = createIndexDirectory(indexFile);
	}


	/**
	 * create the index, i.e. read from the biothesaurus file (which
	 * is expected to have normalized synonyms!) and then write it to the index.
	 * 
	 * @throws IOException
	 */
	public void createIndex(String idType) throws IOException {


		ContextGenerator cg = new ContextGenerator(resourcesDir, idType);
		//SnowballAnalyzer sbAnalyzer = new SnowballAnalyzer("English", cg.getStopWords());
//		SnowballAnalyzer sbAnalyzer = new SnowballAnalyzer("English", ContextUtils.STOPWORDS);
		WhitespaceAnalyzer wsAnalyzer = new WhitespaceAnalyzer();
		IndexWriterConfig iwc = new IndexWriterConfig(wsAnalyzer);
		iwc.setOpenMode(OpenMode.CREATE);
		IndexWriter contextIndexWriter = new IndexWriter(indexDirectory, iwc);
		/*
		if(useContextTypes) {
			contextIndexWriter = new IndexWriter(indexDirectory, wsAnalyzer, true);
			System.out.println("useContextTpye = " + useContextTypes);
		} else {
			System.out.println("useContextTpye = " + useContextTypes);
			contextIndexWriter = new IndexWriter(indexDirectory, sbAnalyzer, true);
		}
		*/
		//TermNormalizer normalizer = new TermNormalizer();
		int counter = 0;
		BufferedReader biothesaurusReader = new BufferedReader(new FileReader(
				biothesaurusFile));

		System.out
				.println("Generating index now. This may take quite a while (up to several hours when file is large) ...");
		// now loop thourgh biothesaurus and add entries to the index
		try {

			String line = "";
			while ((line = biothesaurusReader.readLine()) != null) {

				String[] values = line.split("\t");

				// check whether format is OK
				if (values.length != 1) {
					System.err
							.println("ERR: Input file not in expected format. \ncritical line: "
									+ line);
					System.exit(-1);
				}

				// now get the field values
				String id_org = values[0];
				
				showDebug(id_org);
				
//				 make fields
				String context = cg.getContext(id_org);
				
				
				if(useContextTypes) {
					//System.out.println("useContextTpye = " + useContextTypes);
					context = ContextUtils.makeContextTypes(context);
				} else {
					//System.out.println("useContextTpye = " + useContextTypes);
					context = ContextUtils.makeContextTokens(context);
//					System.out.println(context);
				}
				/*
				if(id_org.equals("IL2RA_HUMAN")) {
					System.out.println("FINAL CONTEXT:" + context);
				}
				*/
				//String contextBigrams = Utils.makeUnderScoreBigrams(context);
				//System.out.println(contextBigrams);
				//context = context + " " + contextBigrams;

				/*
				System.out.println(id_org + ": " + context);
				TokenStream stream = sbAnalyzer.tokenStream("English", new StringReader(context));
				for (Token t = stream.next(); t != null; t = stream.next()) {
					System.out.print(t.toString() + " ");

				System.out.println("\n");
				*/
				//Field contextField = new Field(CONTEXT, new StringReader(context));
				Field lookupContextField = new TextField(ContextIndexFieldNames.LOOKUP_CONTEXT_FIELD, context, Store.YES);
				
				//Field idField = new Field(ID_FIELD, id_org, Field.Store.YES,
					//	Field.Index.UN_TOKENIZED);
				Field lookupIdField = new StringField(ContextIndexFieldNames.LOOKUP_ID_FIELD, id_org, Field.Store.YES);
				

				
				// make context field, make document and add to context index
				Document d = new Document();
				//d.add(contextField);
				//d.add(idField);
				d.add(lookupContextField);
				d.add(lookupIdField);
				
				contextIndexWriter.addDocument(d);
				
				
				++counter;
				if (counter % 10000 == 0){
					System.err.println("# entries processed: " + counter);
				}
			}

			// finally optimize the index and close it
			contextIndexWriter.close();

			biothesaurusReader.close();

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

}
