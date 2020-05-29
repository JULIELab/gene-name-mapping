package de.julielab.jules.ae.genemapping.resources;

import de.julielab.java.utilities.FileUtilities;
import de.julielab.jules.ae.genemapping.CandidateFilter;
import de.julielab.jules.ae.genemapping.LuceneCandidateRetrieval;
import de.julielab.jules.ae.genemapping.index.SynonymIndexFieldNames;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Synonym or gene name centric indexer, new as of March 11, 2019. The idea is to save storage and gain more focused
 * gene mention search results by not indexing each synonym of each gene but group the gene ids by all possible
 * synonyms. Thus, each synonym is only stored once and references the list of genes it may refer to, immediately
 * showing the ambiguity of the synonym.
 */
public class NameCentricSynonymIndexGenerator {

    private static final Logger log = LoggerFactory.getLogger(NameCentricSynonymIndexGenerator.class);

    private static final Boolean OMIT_FILTERED = true;

    Map<String, String> id2tax;

    Directory indexDirectory;
    /**
     * A file containing gene or protein names / synonyms and their respective NCBI
     * Gene or UniProt ID. No term normalization is expected for this dictionary.
     */
    private File dictFile;

    /**
     * @param dictFile  A file containing gene or protein names / synonyms and their
     *                  respective NCBI Gene or UniProt ID. No term normalization is
     *                  expected for this dictionary.
     * @param indexFile The directory where the name / synonym index will be written to.
     * @throws FileNotFoundException
     * @throws IOException
     */
    public NameCentricSynonymIndexGenerator(File dictFile, File indexFile) throws FileNotFoundException, IOException {
        System.out.println("Building synonym index from dictionary " + dictFile.getAbsolutePath());
        this.dictFile = dictFile;
        indexDirectory = createIndexDirectory(indexFile);

    }

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

        File upDictFile = new File(resPath + "gene.dict.variants.norm.up");
        //checkFile(upDictFile);

        File egDictFile = new File(resPath + "gene.dict.variants.norm.filtered.eg");
        checkFile(egDictFile);

        File upTaxMap = new File(resPath + "up2eg2tax.map");
        checkFile(upTaxMap);

        File egTaxMap = geneInfo;

        NameCentricSynonymIndexGenerator indexGenerator;
        try {
            // indexGenerator = new NameCentricSynonymIndexGenerator(upDictFile, proteinIndexDir);
            // indexGenerator.readUpTaxMap(upTaxMap);
            // indexGenerator.createIndex();
            indexGenerator = new NameCentricSynonymIndexGenerator(egDictFile, geneIndexDir);
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
     * Creates the synonym index. Each unique synonym is indexed in a document of its own. Each such document
     * has a number of fields for each gene that has the current synonym and lists the gene ID, its tax ID (if the
     * tax ID mapping is given) and the "priority" that the synonym has for the gene. The priority aims to describe
     * the reliability of the source given the respective synonym. Higher numbers mean a lower priority.
     * The official gene symbol has priority -1.
     *
     * @throws IOException
     */
    public void createIndex() throws IOException {

        CandidateFilter cf = new CandidateFilter();

        WhitespaceAnalyzer wsAnalyzer = new WhitespaceAnalyzer();
        IndexWriterConfig iwc = new IndexWriterConfig(wsAnalyzer);
        iwc.setOpenMode(OpenMode.CREATE);

        log.info(
                "Generating index now. This may take quite a while (up to several hours when input files are large) ...");
        log.info("Using up to 20 threads for index document creation");
        // VERY IMPORTANT: The dictionary file must be sorted by synonym. This is because we want to group the
        // dictionary entries by synonym but we don't want to read the whole dictionary and sort it in-memory
        // because this may well exhaust the memory for the full all-species dictionary.
        final ExecutorService executorService = Executors.newFixedThreadPool(20);
        try (IndexWriter iw = new IndexWriter(indexDirectory, iwc)) {
            try (final BufferedReader br = FileUtilities.getReaderFromFile(dictFile)) {
                AtomicInteger counter = new AtomicInteger();


                String line;
                String currentSynonym = null;
                List<String[]> entriesForCurrentSynonym = new ArrayList<>();
                while ((line = br.readLine()) != null) {
                    final String[] split = line.split("\t");
                    if (split.length != 3)
                        System.err.println("ERR: normalized dictionary not in expected format. \ncritical line: " + line);
                    String synonym = split[0];
                    if (currentSynonym == null)
                        currentSynonym = synonym;

                    // Have we reached the next synonym? Then we must first create the index items for the current
                    // synonym before we continue
                    if (!synonym.equals(currentSynonym)) {
                        final String synonymToWrite = currentSynonym;
                        final List<String[]> entriesToWrite = new ArrayList<>(entriesForCurrentSynonym);
                        executorService.submit(() -> {
                            try {
                                indexCurrentSynonymEntries(cf, iw, counter, synonymToWrite, entriesToWrite);
                            } catch (IOException e) {
                                log.error("Could not create index document for synonym {}", synonymToWrite, e);
                            }
                        });
                        entriesForCurrentSynonym.clear();
                    }

                    entriesForCurrentSynonym.add(split);
                    currentSynonym = synonym;
                }


            } finally {
                try {
                    log.info("Waiting for running threads to terminate within 5 minutes.");
                    executorService.awaitTermination(5, TimeUnit.MINUTES);
                    log.info("Shutting down executor.");
                    executorService.shutdown();
                } catch (InterruptedException e) {
                    log.warn("Waiting for running threads to finish has been interrupted. Shutting down the executor service now.");
                    executorService.shutdownNow();
                }
                log.info("ExecutorService has been shut down.");
            }
            log.info("Committing all index additions.");
            iw.commit();
            // Minimize the index. This will probably not really make the index much smaller since we do not
            // have any deleted documents but at least we won't have so many segments. This step is optional.
            log.info("Merging the index to one segment.");
            iw.forceMerge(1);
        }
    }

    /**
     * Takes the arrays with the gene IDs that have the passed synonym with the priorities that are also stored in the arrays given by <tt>entriesForCurrentSynonym</tt>.
     * Creates one Lucene document for the synonym and sets all the IDs with their priorities into one field (separated by {@link LuceneCandidateRetrieval#NAME_PRIO_DELIMITER}) and the respective taxonomy IDs in another field.
     * The priorities are assigned in the _makeGeneDictionary.sh script and should reflect the reliability of the source that
     * gave the corresponding synonym tot he gene.
     *
     * @param cf                       The candidate filter for filtering out synonyms that look as they wouldn't help at all.
     * @param iw                       The Lucene index writer.
     * @param counter                  A parameter for counting the number of synonyms processed, for status output.
     * @param currentSynonym           The synonym for which all entries have been collected in <tt>entriesForCurrentSynonym</tt>.
     * @param entriesForCurrentSynonym All the IDs of genes that have the <tt>currentSynonym</tt> and the priority with which they have the synonym.
     * @return The counter for status output. The generated document is added to the IndexWriter within the method.
     * @throws IOException
     */
    private void indexCurrentSynonymEntries(CandidateFilter cf, IndexWriter iw, AtomicInteger counter, String currentSynonym, List<String[]> entriesForCurrentSynonym) throws IOException {
        Document doc = new Document();
        Field lookupSynField = new TextField(SynonymIndexFieldNames.LOOKUP_SYN_FIELD, currentSynonym,
                Store.YES);
        doc.add(lookupSynField);
        List<Field> fields = new ArrayList<>();
        for (String[] geneEntry : entriesForCurrentSynonym) {
            String id = geneEntry[1];
            Integer priority = Integer.parseInt(geneEntry[2]);

            boolean filtered = false;
            // If the synonym is the official gene symbol, we accept it, no matter what
            if (!OMIT_FILTERED && priority != -1) {
                filtered = DictionaryFamilyDomainFilter.isFiltered(cf, currentSynonym);
            }
            if (log.isDebugEnabled()) {
                log.debug("ID: {}, synonym: {}, filtered out: {}", id, currentSynonym, filtered);
            }

            String tax = "";
            if (id2tax.get(id) != null) {
                tax = id2tax.get(id);
            }


            Field idField = new StringField(SynonymIndexFieldNames.ID_FIELD, id + LuceneCandidateRetrieval.NAME_PRIO_DELIMITER + priority, Store.YES);
            Field taxField = new StringField(SynonymIndexFieldNames.TAX_ID_FIELD, tax, Store.YES);
            if (!OMIT_FILTERED) {
                IntPoint filteredField = new IntPoint(SynonymIndexFieldNames.FILTERED, filtered ? 1 : 0);
                StoredField storedFilteredField = new StoredField(SynonymIndexFieldNames.FILTERED,
                        filtered ? 1 : 0);
                fields.add(filteredField);
                fields.add(storedFilteredField);
            }
            fields.add(idField);
            fields.add(taxField);

        }
        if (!fields.isEmpty()) {
            for (Field f : fields)
                doc.add(f);

            iw.addDocument(doc);
        }

        int done = counter.incrementAndGet();
        if (done % 10000 == 0) {
            log.debug("# entries processed: " + done);
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
