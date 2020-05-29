/**
 * IndexGenerator.java
 * <p>
 * Copyright (c) 2006, JULIE Lab.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * <p>
 * Author: wermter
 * <p>
 * Current version: 1.5.1
 * Since version:   1.0
 * <p>
 * Creation date: Nov 30, 2006
 * <p>
 * This class puts the semantic context for a given gene dictionary into
 * a Lucene index.
 **/

package de.julielab.jules.ae.genemapping.resources;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import de.julielab.java.utilities.FileUtilities;
import de.julielab.jules.ae.genemapping.index.ContextIndexFieldNames;
import de.julielab.jules.ae.genemapping.utils.norm.TermNormalizer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

public class SynonymDisambiguationIndexGenerator {
    private final static Logger log = LoggerFactory.getLogger(SynonymDisambiguationIndexGenerator.class);
    private Directory indexDirectory;
    private File idFile;
    private String resourcesDir;

    /**
     * constructor which creates index in the specified directory on the disk
     */
    public SynonymDisambiguationIndexGenerator(File idFile,
                                               File indexFile, String resourcesDir)  {
        this.idFile = idFile;
        this.resourcesDir = resourcesDir;
        indexDirectory = createIndexDirectory(indexFile);
    }

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
            System.err.println("Usage: SynonymDisambiguationIndexGenerator <resourcesDirectory> <geneContextIndicesDirectory>");
            System.exit(-1);
        }

        String indexBaseDir = args[1];
        if (!indexBaseDir.endsWith(File.separator)) {
            indexBaseDir = indexBaseDir + File.separator;
        }
        File geneIndexDir = new File(indexBaseDir + "geneContextItemsIndex");
        File proteinIndexDir = new File(indexBaseDir + "proteinContextItemsIndex");

        String resPath = args[0];
        File resDir = new File(resPath);
        if (!resDir.isDirectory()) {
            System.err.println("Could not find resources directory");
            System.exit(-1);
        }
        if (!resPath.endsWith(File.separator)) {
            resPath = resPath + File.separator;
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

        SynonymDisambiguationIndexGenerator indexGenerator;
        try {
            indexGenerator = new SynonymDisambiguationIndexGenerator(upFile, proteinIndexDir, resPath);
            indexGenerator.createIndex("protein");
            indexGenerator = new SynonymDisambiguationIndexGenerator(egFile, geneIndexDir, resPath);
            indexGenerator.createIndex("gene");
        } catch (IOException e) {
            e.printStackTrace();
        }

        long s2 = System.currentTimeMillis();
        System.out.println("Indices created successfully! (" + (s2 - s1) / 1000 + " sec)");
    }

    /**
     * create the index, i.e. read from the biothesaurus file (which
     * is expected to have normalized synonyms!) and then write it to the index.
     *
     * @throws IOException
     */
    public void createIndex(String idType) throws IOException {

        String typePrefix = idType.equals("gene") ? "eg" : "up";

        Map<String, Multimap<String, String>> contextMaps = new HashMap<>();
        readContextInformation(Path.of(resourcesDir, typePrefix + "2generif").toFile(), contextMaps, ContextIndexFieldNames.FIELD_GENERIF);
        readContextInformation(Path.of(resourcesDir, typePrefix + "2interaction").toFile(), contextMaps, ContextIndexFieldNames.FIELD_INTERACTION);
        readContextInformation(Path.of(resourcesDir, typePrefix + "2summary").toFile(), contextMaps, ContextIndexFieldNames.FIELD_SUMMARY);

        IndexWriterConfig iwc = new IndexWriterConfig(new WhitespaceAnalyzer());
        iwc.setOpenMode(OpenMode.CREATE);
        IndexWriter contextIndexWriter = new IndexWriter(indexDirectory, iwc);
        TermNormalizer normalizer = new TermNormalizer();
        int counter = 0;

        log.info("Writing index {}", indexDirectory);
        try (BufferedReader idReader = new BufferedReader(new FileReader(idFile))) {

            String id;
            while ((id = idReader.readLine()) != null) {

                List<Field> fields = new ArrayList<>();
                for (String contextField : contextMaps.keySet()) {
                    final Collection<String> contextItems = contextMaps.get(contextField).get(id);
                    if (contextItems != null) {
                        Field lookupIdField = new StringField(ContextIndexFieldNames.LOOKUP_ID_FIELD, id, Store.YES);
                        fields.add(lookupIdField);
                        for (String contextItem : contextItems) {
                            Field lookupContextField = new TextField(contextField, normalizer.normalize(contextItem), Store.NO);
                            fields.add(lookupContextField);
                        }
                    }
                }
                Document d = new Document();
                for (Field f : fields)
                    d.add(f);


                contextIndexWriter.addDocument(d);


                ++counter;
                if (counter % 10000 == 0) {
                    log.debug("# entries processed: " + counter);
                }
            }

            // finally optimize the index and close it
            contextIndexWriter.forceMerge(1);
            contextIndexWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        log.info("Done writing context item index.");

    }

    private void readContextInformation(File contextFile, Map<String, Multimap<String, String>> contextMaps, String fieldname) throws IOException {
        Multimap<String, String> context = HashMultimap.create();
        try (final BufferedReader br = FileUtilities.getReaderFromFile(contextFile)) {
            br.lines().filter(Predicate.not(line -> line.startsWith("#"))).map(line -> line.split("\t")).forEach(split -> context.put(split[0].intern(), split[1]));
        }
        log.info("Reading context file {} with {} entries", contextFile, context.size());
        contextMaps.put(fieldname, context);
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

}
