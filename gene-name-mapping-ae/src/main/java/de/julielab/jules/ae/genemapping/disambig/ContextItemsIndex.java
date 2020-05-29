/**
 * SemanticDisambiguation.java
 * <p>
 * Copyright (c) 2008, JULIE Lab.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * <p>
 * Author: tomanek
 * <p>
 * Current version: 1.6
 * Since version:   1.6
 * <p>
 * Creation date: Feb 4, 2008
 * <p>
 * does the semantic disambiguation using the context of each of the candidate ids
 **/

package de.julielab.jules.ae.genemapping.disambig;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import de.julielab.jules.ae.genemapping.ContextItemsCacheKey;
import de.julielab.jules.ae.genemapping.GeneMappingConfiguration;
import de.julielab.jules.ae.genemapping.SynHit;
import de.julielab.jules.ae.genemapping.index.ContextIndexFieldNames;
import de.julielab.jules.ae.genemapping.utils.GeneMappingException;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.*;
import org.apache.lucene.search.BooleanQuery.Builder;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class ContextItemsIndex implements SemanticIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextItemsIndex.class);
    /**
     * This static map is supposed to make candidate caches available for all
     * instances of this class across the JVM. This is important since we often
     * use multiple gene-mapper instances in the same pipeline. It can save a
     * lot of time and also space.
     */
    private static ConcurrentHashMap<String, LoadingCache<ContextItemsCacheKey, Collection<String>>> caches = new ConcurrentHashMap<>();
    public IndexSearcher searcher;
    private LoadingCache<ContextItemsCacheKey, Collection<String>> geneContextCache;

    public ContextItemsIndex(GeneMappingConfiguration configuration) throws GeneMappingException {
        final String indexDirPath = configuration.getProperty(GeneMappingConfiguration.CONTEXT_ITEMS_INDEX);
        if (indexDirPath == null)
            throw new GeneMappingException("context items index not specified in configuration file (critical).");
        try {
            File indexDir = new File(indexDirPath);
            IndexReader reader = DirectoryReader.open(FSDirectory.open(indexDir.toPath()));
            searcher = new IndexSearcher(reader);

            LOGGER.info("using " + indexDir.getAbsolutePath() + " as synonym disambiguation index with "
                    + searcher.getIndexReader().numDocs() + " gene entries");

            String indexPath = indexDir.getCanonicalPath();
            synchronized (caches) {
                geneContextCache = caches.get(indexPath);
                if (geneContextCache == null) {
                    LOGGER.info("Creating new gene context cache for index {}", indexPath);
                    geneContextCache = CacheBuilder.newBuilder().maximumSize(10000).expireAfterWrite(10, TimeUnit.MINUTES)
                            .build(new CacheLoader<>() {
                                @Override
                                public Collection<String> load(ContextItemsCacheKey contextItemsCacheKey) throws Exception {
                                    return getContextItemsFromIndex(contextItemsCacheKey);
                                }
                            });
                    if (null != caches.put(indexPath, geneContextCache))
                        throw new IllegalStateException("There already is a candidate index for " + indexPath
                                + " which points to a faulty concurrency implementation");
                } else {
                    LOGGER.info("Using existing gene context cache for index {}", indexPath);
                }
            }
        } catch (IOException e) {
            throw new GeneMappingException(e);
        }
    }

    public Collection<String> getContextItems(ContextItemsCacheKey key) throws ExecutionException {
        return geneContextCache.get(key);
    }

    public Collection<String> getContextItems(String geneId, String field) throws ExecutionException {
        return getContextItems(new ContextItemsCacheKey(geneId, field));
    }

    private Collection<String> getContextItemsFromIndex(ContextItemsCacheKey key) throws IOException {
        final TermQuery termQuery = new TermQuery(new Term(ContextIndexFieldNames.LOOKUP_ID_FIELD, key.getGeneId()));
        final BooleanQuery filterQuery = new Builder().add(new BooleanClause(termQuery, Occur.FILTER)).build();
        final TopDocs result = searcher.search(filterQuery, 1);
        if (result.totalHits > 0) {
            List<String> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : result.scoreDocs) {
                final Document doc = searcher.doc(scoreDoc.doc);
                final IndexableField[] fields = doc.getFields(key.getIndexField());
                for (IndexableField f : fields) {
                    results.add(f.stringValue());
                }
            }
            return results;
        }
        return Collections.emptyList();
    }

    /**
     * @param synHit
     * @return
     */
    public Map<String, Float> getSynonymRefSeqScoresForTaxIds(SynHit synHit, Set<String> taxonomyIds) throws IOException {
        final List<String> idsForTaxIds = IntStream.range(0, synHit.getIds().size()).filter(i -> taxonomyIds.contains(synHit.getTaxIds().get(i))).mapToObj(i -> synHit.getIds().get(i)).collect(Collectors.toList());
        final Stream<TermQuery> termQueryStream = idsForTaxIds.stream().map(id -> new TermQuery(new Term(ContextIndexFieldNames.LOOKUP_ID_FIELD, id)));
        final Builder filterQueryBuilder = new Builder();
        // add the IDs as filters
        termQueryStream.forEach(q -> filterQueryBuilder.add(new BooleanClause(q, Occur.SHOULD)));
        final BooleanQuery filterQuery = filterQueryBuilder.build();

        final Builder queryBuilder = new Builder();
        queryBuilder.add(new BooleanClause(filterQuery, Occur.FILTER));
        // Create a phrase query for the synonym for multi word synonyms
        final PhraseQuery.Builder synonymQueryBuilder = new PhraseQuery.Builder().setSlop(0);
        Stream.of(synHit.getSynonym()).flatMap(s -> Stream.of(s.split("\\s+"))).forEach(t -> synonymQueryBuilder.add(new Term(ContextIndexFieldNames.FIELD_GENERIF, t)));
        final PhraseQuery synonymPhraseQuery = synonymQueryBuilder.build();
        queryBuilder.add(new BooleanClause(synonymPhraseQuery, Occur.MUST));
        final BooleanQuery query = queryBuilder.build();

        Map<String, Float> map = new HashMap<>();
        for (String geneId : idsForTaxIds)
            map.put(geneId, 0f);
        final TopDocs topDocs = searcher.search(query, idsForTaxIds.size());
        if (topDocs.totalHits > 0) {
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                final Document doc = searcher.doc(scoreDoc.doc);
                final String id = doc.get(ContextIndexFieldNames.LOOKUP_ID_FIELD);
                final float score = scoreDoc.score;
                map.put(id, score);
            }
        }

        return map;
    }


    public IndexSearcher getContextItemsSearcher() {
        return searcher;
    }

}
