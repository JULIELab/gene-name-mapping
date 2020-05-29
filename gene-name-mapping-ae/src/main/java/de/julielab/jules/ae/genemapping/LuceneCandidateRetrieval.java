package de.julielab.jules.ae.genemapping;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import de.julielab.jules.ae.genemapping.SynHit.CompareType;
import de.julielab.jules.ae.genemapping.genemodel.GeneMention;
import de.julielab.jules.ae.genemapping.genemodel.GeneName;
import de.julielab.jules.ae.genemapping.index.SynonymIndexFieldNames;
import de.julielab.jules.ae.genemapping.scoring.Scorer;
import de.julielab.jules.ae.genemapping.scoring.*;
import de.julielab.jules.ae.genemapping.utils.GeneCandidateRetrievalException;
import de.julielab.jules.ae.genemapping.utils.GeneMappingException;
import de.julielab.jules.ae.genemapping.utils.norm.TermNormalizer;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.spell.SpellChecker;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LuceneCandidateRetrieval implements CandidateRetrieval {
    public static final String NAME_PRIO_DELIMITER = "__";

    public static final String LOGGER_NAME_CANDIDATES = "de.julielab.jules.ae.genemapper.candidates";
    public static final int SIMPLE_SCORER = 0;
    public static final int TOKEN_JAROWINKLER_SCORER = 1;
    public static final int MAXENT_SCORER = 2;
    public static final int JAROWINKLER_SCORER = 3;
    public static final int LEVENSHTEIN_SCORER = 4;
    public static final int TFIDF = 5;
    public static final int LUCENE_SCORER = 10;
    /**
     * default model for MaxEntScorer
     */
    public static final String MAXENT_SCORER_MODEL = "/genemapper_jules_mallet.mod";
    public static final Logger candidateLog = LoggerFactory.getLogger(LOGGER_NAME_CANDIDATES);
    private static final Logger log = LoggerFactory.getLogger(LuceneCandidateRetrieval.class);
    /**
     * the maximal number of hits lucene returns for a query
     */
    private static final int LUCENE_MAX_HITS = 20;


    /**
     * This static map is supposed to make candidate caches available for all
     * instances of this class across the JVM. This is important since we often
     * use multiple gene-mapper instances in the same pipeline. It can save a
     * lot of time and also space.
     */
    private static ConcurrentHashMap<String, LoadingCache<CandidateCacheKey, List<SynHit>>> caches = new ConcurrentHashMap<>();
    // the model to be loaded for MaxEnt scorer
    // (can be specified in properties file)
    private String maxEntModel = MAXENT_SCORER_MODEL;
    private TermNormalizer normalizer;
    private IndexSearcher mentionIndexSearcher;
    private Scorer exactScorer;
    private Scorer approxScorer;
    private LoadingCache<CandidateCacheKey, List<SynHit>> candidateCache;
    private SpellChecker spellingChecker;

    @Deprecated
    public LuceneCandidateRetrieval(IndexSearcher mentionIndexSearcher, Scorer scorer) throws IOException {
        this.mentionIndexSearcher = mentionIndexSearcher;
        this.exactScorer = scorer;
        normalizer = new TermNormalizer();
    }

    public LuceneCandidateRetrieval(GeneMappingConfiguration config) throws GeneMappingException {

        // lucene mention index
        String mentionIndex = config.getProperty(GeneMappingConfiguration.MENTION_INDEX);
        if (mentionIndex == null) {
            throw new GeneMappingException("mention index not specified in configuration file (critical).");
        }

        try {

            IndexReader reader = DirectoryReader.open(FSDirectory.open(Paths.get(mentionIndex)));
            mentionIndexSearcher = new IndexSearcher(reader);
            // The default since Lucene 5 is BM25. But for our purposes, the
            // classic
            // Lucene Similarit works better (checked with IGN train).
            mentionIndexSearcher.setSimilarity(new ClassicSimilarity());
            log.debug("mention index loaded.");

            String spellingIndexPath = config.getProperty(GeneMappingConfiguration.SPELLING_INDEX);
            if (spellingIndexPath != null) {
                File spellingIndex = new File(spellingIndexPath);
                if (spellingIndex.exists())
                    spellingChecker = new SpellChecker(FSDirectory.open(spellingIndex.toPath()));
            }
            if (spellingChecker == null)
                log.warn(
                        "Spelling index was not given or file does not exist. No spelling correction can be done. Specified spelling index: {}",
                        spellingIndexPath);

            // scorer types
            String scorerType = config.getProperty(GeneMappingConfiguration.EXACT_SCORER_TYPE);
            if (scorerType == null)
                throw new GeneMappingException("No configuration value given for " + GeneMappingConfiguration.EXACT_SCORER_TYPE);
            exactScorer = setScorerType(Integer.valueOf(scorerType));

            scorerType = config.getProperty(GeneMappingConfiguration.APPROX_SCORER_TYPE);
            if (scorerType == null)
                throw new GeneMappingException("No configuration value given for " + GeneMappingConfiguration.APPROX_SCORER_TYPE);
            approxScorer = setScorerType(Integer.valueOf(scorerType));

            // maxent model
            String maxEntModel = config.getProperty("maxent_model");
            if (maxEntModel != null) {
                this.maxEntModel = maxEntModel;
            }

            this.normalizer = new TermNormalizer();
        } catch (IOException e) {
            throw new GeneMappingException(e);
        }

        log.info("Mention index: " + mentionIndex);
        log.info("Exact scorer: " + exactScorer);
        log.info("Approx scorer: " + approxScorer);

        // get an existing candidate cache for the given mention index or create
        // it; the lock avoids the creation of multiple caches due to
        // concurrency issues
        synchronized (caches) {
            candidateCache = caches.get(mentionIndex);
            if (null == candidateCache) {
                log.info("Creating new gene candidate cache for index {}", mentionIndex);
                candidateCache = CacheBuilder.newBuilder().maximumSize(1000000).expireAfterWrite(60, TimeUnit.MINUTES)
                        .build(new CacheLoader<>() {
                            public List<SynHit> load(CandidateCacheKey key)
                                    throws IOException, BooleanQuery.TooManyClauses {
                                return Collections.unmodifiableList(getCandidatesFromIndexWithoutCache(key));
                            }
                        });
                if (null != caches.put(mentionIndex, candidateCache))
                    throw new IllegalStateException("There already is a candidate index for " + mentionIndex
                            + " which points to a faulty concurrency implementation");
            } else {
                log.info("Using existing gene candidate cache for index {}", mentionIndex);
            }
        }
    }

    public TermNormalizer getNormalizer() {
        return normalizer;
    }

    public void setNormalizer(TermNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public Scorer getScorer() {
        return exactScorer;
    }

    public IndexSearcher getMentionIndexSearcher() {
        return mentionIndexSearcher;
    }

    public SpellChecker getSpellingChecker() {
        return spellingChecker;
    }

    public Scorer setScorerType(int type) throws GeneMappingException {
        Scorer scorer;
        if (type == SIMPLE_SCORER) {
            scorer = new SimpleScorer();
        } else if (type == TOKEN_JAROWINKLER_SCORER) {
            scorer = new TokenJaroSimilarityScorer();
        } else if (type == MAXENT_SCORER) {
            if (!maxEntModel.equals(MAXENT_SCORER_MODEL)) {
                scorer = new MaxEntScorer(new File(maxEntModel));
            } else {
                InputStream in = this.getClass().getResourceAsStream(MAXENT_SCORER_MODEL);
                scorer = new MaxEntScorer(in);
            }
        } else if (type == JAROWINKLER_SCORER) {
            scorer = new JaroWinklerScorer();
        } else if (type == LUCENE_SCORER) {
            scorer = new LuceneScorer();
        } else if (type == LEVENSHTEIN_SCORER) {
            scorer = new LevenshteinScorer();
        }  else {
            throw new GeneMappingException("Unknown mention scorer type: " + type);
        }
        return scorer;
    }

    public String getScorerInfo() {
        if (exactScorer == null) {
            return "Lucene Score (unnormalized)";
        } else {
            return exactScorer.info();
        }
    }

    public int getScorerType() {
        return exactScorer.getScorerType();
    }

    @Override
    public List<SynHit> getCandidates(String originalSearchTerm) throws GeneCandidateRetrievalException {
        GeneMention geneMention = new GeneMention(originalSearchTerm, normalizer);
        return getCandidates(geneMention);
    }

    @Override
    public List<SynHit> getCandidates(GeneMention geneMention) throws GeneCandidateRetrievalException {
        return getCandidates(geneMention, geneMention.getTaxonomyIds());
    }

    @Override
    public List<SynHit> getCandidates(GeneMention geneMention, Collection<String> organisms)
            throws GeneCandidateRetrievalException {
        try {
            List<SynHit> hits = new ArrayList<>();
            CandidateCacheKey key = new CandidateCacheKey(geneMention.getGeneName());
            if (organisms.isEmpty()) {
                hits = getCandidatesFromIndex(key);
                if (log.isDebugEnabled()) {
                    int geneBegin = geneMention.getOffsets() != null ? geneMention.getBegin() : -1;
                    int geneEnd = geneMention.getOffsets() != null ? geneMention.getEnd() : -1;
                    log.debug("Returning {} candidates for gene mention {}[{}-{}]", hits.size(), key.geneName.getText(), geneBegin, geneEnd);
                }
            }
            for (String taxonomyId : organisms) {
                key.taxId = taxonomyId;
                hits.addAll(getCandidatesFromIndex(key));
                // TopDocs foundDocs = getCandidatesFromIndex(key);
                // 2. assign score
                // List<SynHit> scoredHits = new ArrayList<SynHit>();
                // scoredHits = scoreHits(foundDocs, key.geneName);
                // 3. combine single hits to candidate clusters
                // ArrayList<SynHit> hits = combineHits(scoredHits);
                // 4. sort by SynHit's score (lucene score)

                if (log.isDebugEnabled()) {
                    int begin = -1;
                    int end = -1;
                    if (geneMention.getOffsets() != null) {
                        begin = geneMention.getBegin();
                        end = geneMention.getEnd();
                    }
                    log.debug("Returning {} candidates for gene mention {}[{}-{}] for taxonomy ID {}",
                            hits.size(), key.geneName.getText(), begin, end, organisms);
                }
            }
            //hits = combineHits(hits);
            hits.stream().forEach(h -> h.setCompareType(CompareType.SCORE));
            List<SynHit> sortedHits = hits.stream().sorted().collect(Collectors.toList());
            return sortedHits;
        } catch (ExecutionException e) {
            throw new GeneCandidateRetrievalException(e);
        }
    }


    /**
     * This is the method that access the cache. This is important because before the SynHits are returned,
     * they must be cloned or changed on them will write back into the cache.
     *
     * @param key The cache key.
     * @return A new list that contains copies of the cached SynHits.
     * @throws ExecutionException If there is an issue with the cache.
     */
    private List<SynHit> getCandidatesFromIndex(CandidateCacheKey key) throws ExecutionException {
        return candidateCache.get(key).stream().map((SynHit synHit) -> {
            try {
                return synHit.clone();
            } catch (CloneNotSupportedException e) {
                log.error("Could not clone a cached SynHit: {}", synHit, e);
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
    }

    private ArrayList<SynHit> getCandidatesFromIndexWithoutCache(CandidateCacheKey key)
            throws IOException, BooleanQuery.TooManyClauses {
        Query searchQuery = QueryGenerator.makeDisjunctionMaxQuery(key, spellingChecker);
        TopDocs foundDocs = mentionIndexSearcher.search(searchQuery, LUCENE_MAX_HITS);
        log.debug("searching with query: " + searchQuery + "; found hits: " + foundDocs.totalHits);
        return scoreHits(foundDocs, key.geneName);
    }

    /**
     * calculate score for each hit
     *
     * @param foundDocs
     * @param geneName
     * @throws IOException
     * @throws CorruptIndexException
     * @throws Exception
     */
    private ArrayList<SynHit> scoreHits(TopDocs foundDocs, GeneName geneName)
            throws CorruptIndexException, IOException {
        ArrayList<SynHit> allHits = new ArrayList<>();

        String originalMention = geneName.getText().toLowerCase();
        String normalizedMention = geneName.getNormalizedText();

        ScoreDoc[] scoredDocs = foundDocs.scoreDocs;
        log.debug("ordering candidates for best match to this reference term: " + originalMention + " for top "
                + scoredDocs.length + " candidates");
        candidateLog.trace("Search term: " + normalizedMention);
        for (int i = 0; i < scoredDocs.length; i++) {
            int docID = scoredDocs[i].doc;
            Document d = mentionIndexSearcher.doc(docID);
            String indexNormalizedName = d.getField(SynonymIndexFieldNames.LOOKUP_SYN_FIELD).stringValue();
            List<String> ids = new ArrayList<>();
            List<Number> priorities = new ArrayList<>();
            Arrays.stream(d.getFields(SynonymIndexFieldNames.ID_FIELD)).map(IndexableField::stringValue).map(idAndSyn -> idAndSyn.split(NAME_PRIO_DELIMITER)).forEach(split -> {
                ids.add(split[0]);
                priorities.add(Integer.valueOf(split[1]));
            });
            List<String> taxIds = Arrays.stream(d.getFields(SynonymIndexFieldNames.TAX_ID_FIELD)).map(IndexableField::stringValue).collect(Collectors.toList());


            double score = 0;
            Scorer scorer = indexNormalizedName.equals(normalizedMention) ? exactScorer : approxScorer;
            if (scorer.getScorerType() == GeneMapping.LUCENE_SCORER) {
                // use Lucene scoring
                if (indexNormalizedName.equals(normalizedMention)) {
                    // exact matches get perfect score
                    score = Scorer.PERFECT_SCORE;
                    // score = scoredDocs[i].score;
                } else {
                    // approximate matches get lucene score
                    score = scoredDocs[i].score;
                }
                // Actually, using the DisMax query, another index field might
                // have given the best hit; but we can't say which. The
                // normalized mention is a reasonable choice because the very
                // most good hits stem from normalized variants.
            } else {
                // use external scoring
                score = scorer.getScore(normalizedMention, indexNormalizedName);
            }
            // now make a new synhit object
            // TODO write source into the index (NCBI Gene or UniProt)
            SynHit m = new SynHit(indexNormalizedName, score, ids, GeneMapping.SOURCE_DEFINITION, taxIds);
            m.setMappedMention(originalMention);
            m.setMappedGeneName(geneName);
            m.setSynonymPriorities(priorities);
            allHits.add(m);
        }

        return allHits;
    }

    @Override
    public List<SynHit> getCandidates(GeneMention geneMention, String organism) throws GeneCandidateRetrievalException {
        return getCandidates(geneMention, Arrays.asList(organism));
    }

    @Override
    public List<SynHit> getCandidates(String geneMentionText, String organism) throws GeneCandidateRetrievalException {
        return getCandidates(new GeneMention(geneMentionText, normalizer), Arrays.asList(organism));
    }

    @Override
    public List<SynHit> getCandidates(String geneMentionText, Collection<String> organism)
            throws GeneCandidateRetrievalException {
        return getCandidates(new GeneMention(geneMentionText, normalizer), organism);
    }

    @Override
    /**
     * This will look up the Gene ID of a gene mention in the Lucene index and
     * return the matching Taxonomy IDs for it.
     *
     * @param geneId
     *            A Gene ID to look up in the index
     * @return A Taxonomy ID
     */
    public String mapGeneIdToTaxId(String geneId) throws IOException {
        final String fieldValue = geneId + LuceneCandidateRetrieval.NAME_PRIO_DELIMITER + -1;
        TermQuery query = new TermQuery(new Term(SynonymIndexFieldNames.ID_FIELD, fieldValue));
        TopDocs topDocs = mentionIndexSearcher.search(query, 1);
        ScoreDoc[] scoredDocs = topDocs.scoreDocs;
        // As mappings should be unique, the set should have a size of one.
        if (topDocs.totalHits > 0) {
            int docID = scoredDocs[0].doc;
            Document d = mentionIndexSearcher.doc(docID);
            final List<String> ids = Arrays.stream(d.getFields(SynonymIndexFieldNames.ID_FIELD)).map(IndexableField::stringValue).map(idandprio -> idandprio.split(LuceneCandidateRetrieval.NAME_PRIO_DELIMITER)).map(split -> split[0]).collect(Collectors.toList());
            final List<String> taxIds = Arrays.stream(d.getFields(SynonymIndexFieldNames.TAX_ID_FIELD)).map(IndexableField::stringValue).collect(Collectors.toList());
            String taxId = "";
            for (int i = 0; i < ids.size(); i++) {
                if (ids.get(i).equals(geneId))
                    taxId = taxIds.get(i);
            }
            if (taxId.equals("")) {
                log.warn("GeneID: " + geneId + " has no TaxId assigned.");
            }
            return taxId;
        }

        return "";
    }

    public List<SynHit> getIndexEntries(List<String> ids) throws IOException {
        log.warn("LuceneCandidateRetrieval.getIndexEntries(): This method currently does not work as intended since the synonym index is now synonym-centric instead of id-centric. The ID field values have the form id_priority, thus at this place a wildcard query for all priorities would be needed");
        List<SynHit> entries = new ArrayList<>(ids.size());
        for (String id : ids) {
            BooleanClause clause = new BooleanClause(new TermQuery(new Term(SynonymIndexFieldNames.ID_FIELD, id + LuceneCandidateRetrieval.NAME_PRIO_DELIMITER + "-1")),
                    Occur.FILTER);
            BooleanQuery query = new BooleanQuery.Builder().add(clause).build();
            TopDocs result = mentionIndexSearcher.search(query, 1);
            if (result.totalHits > 0) {
                int docID = result.scoreDocs[0].doc;
                Document d = mentionIndexSearcher.doc(docID);
                List<String> taxIdField = Arrays.stream(d.getFields(SynonymIndexFieldNames.TAX_ID_FIELD)).map(IndexableField::stringValue).filter(tax -> !StringUtils.isBlank(tax)).collect(Collectors.toList());
                if (taxIdField.isEmpty()) {
                    log.warn("GeneID: " + id + " has no TaxId assigned.");
                }
                SynHit m = new SynHit("<none>", 0d, Arrays.asList(id), GeneMapping.SOURCE_DEFINITION, taxIdField);
                entries.add(m);
            }
            entries.add(null);
        }
        return entries;
    }

    @Override
    public List<String> getSynonyms(String id) throws IOException {
        List<String> ret = Collections.emptyList();
        BooleanClause clause = new BooleanClause(new WildcardQuery(new Term(SynonymIndexFieldNames.ID_FIELD, id + NAME_PRIO_DELIMITER + "*")),
                Occur.FILTER);
        BooleanQuery query = new BooleanQuery.Builder().add(clause).build();
        int maxRet = 200;
        TopDocs result = mentionIndexSearcher.search(query, maxRet);
        if (result.totalHits > 0) {
            ret = new ArrayList<>(maxRet);
            for (int i = 0; i < result.scoreDocs.length; ++i) {
                Document doc = mentionIndexSearcher.doc(result.scoreDocs[i].doc);
                String geneName = doc.getField(SynonymIndexFieldNames.LOOKUP_SYN_FIELD).stringValue();
                ret.add(geneName);
            }
        }
        return ret;
    }

    public List<String> getPriorityNames(String id, int priority) throws IOException {
        List<String> ret = Collections.emptyList();
        BooleanClause ic = new BooleanClause(new TermQuery(new Term(SynonymIndexFieldNames.ID_FIELD, id + LuceneCandidateRetrieval.NAME_PRIO_DELIMITER + priority)), Occur.FILTER);
        BooleanQuery query = new BooleanQuery.Builder().add(ic).build();
        int maxRet = 1;
        TopDocs result = mentionIndexSearcher.search(query, maxRet);
        if (result.totalHits > 0) {
            ret = new ArrayList<>(maxRet);
            for (int i = 0; i < result.scoreDocs.length; ++i) {
                Document doc = mentionIndexSearcher.doc(result.scoreDocs[i].doc);
                String name = doc.getField(SynonymIndexFieldNames.LOOKUP_SYN_FIELD).stringValue();
                ret.add(name);
            }
        }
        return ret;
    }

    public List<String> getPriorityNames(List<String> ids, int priority) throws IOException {
        final Stream.Builder<List<String>> builder = Stream.builder();
        for (String id : ids) {
            builder.accept(getPriorityNames(id, priority));
        }
        return builder.build().flatMap(Collection::stream).collect(Collectors.toList());
    }

}
