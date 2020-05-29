/**
 * QueryGenerator.java
 * <p>
 * Copyright (c) 2007, JULIE Lab.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * <p>
 * Author: tomanek
 * <p>
 * Current version: 2.0
 * Since version:   1.5
 * <p>
 * Creation date: Oct 30, 2007
 * <p>
 * Builds a boolean query where each token of the search string is added SHOULD (OR).
 **/

package de.julielab.jules.ae.genemapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BooleanQuery.Builder;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.spell.SpellChecker;

import de.julielab.jules.ae.genemapping.index.SynonymIndexFieldNames;

public class QueryGenerator {

    public static BooleanQuery makeDisjunctiveQuery(String searchString) {
        return makeDisjunctiveQuery(searchString, SynonymIndexFieldNames.LOOKUP_SYN_FIELD);
    }

    public static BooleanQuery makeDisjunctiveQuery(String searchString, String field)
            throws BooleanQuery.TooManyClauses {
        if (searchString == null)
            return null;
        String[] tokens = searchString.split(" ");

        Builder disjunctiveQuery = new BooleanQuery.Builder();
        for (int i = 0; i < tokens.length; i++) {
            Query q = new TermQuery(new Term(field, tokens[i]));
            disjunctiveQuery.add(q, BooleanClause.Occur.SHOULD);
        }
        return disjunctiveQuery.build();
    }

    public static BooleanQuery makeConjunctiveQuery(String searchString, String field) {
        String[] tokens = searchString.split(" ");

        Builder disjunctiveQuery = new BooleanQuery.Builder();
        for (int i = 0; i < tokens.length; i++) {
            Query q = new TermQuery(new Term(field, tokens[i]));
            disjunctiveQuery.add(q, BooleanClause.Occur.MUST);
        }
        return disjunctiveQuery.build();
    }

    public static Query makeDisjunctionMaxQuery(CandidateCacheKey key, SpellChecker spellingChecker)
            throws BooleanQuery.TooManyClauses, IOException {
        String originalName = key.geneName.getText().toLowerCase();
        String normalizedName = key.geneName.getNormalizedText();
        List<String> nameVariant = key.geneName.getNormalizedTextVariant();
        //	normalizedName = applySpellingCorrection(normalizedName, spellingChecker);
        List<Query> disjuncts = new ArrayList<>();
        BooleanQuery originalNameQueryDisjunctive = makeDisjunctiveQuery(originalName,
                SynonymIndexFieldNames.ORIGINAL_NAME);
        BooleanQuery normalizedNameQueryDisjunctive = makeDisjunctiveQuery(normalizedName,
                SynonymIndexFieldNames.LOOKUP_SYN_FIELD);
        disjuncts.add(originalNameQueryDisjunctive);
        disjuncts.add(normalizedNameQueryDisjunctive);
        for (String variant : nameVariant) {
            if (!variant.equals(originalName) && !variant.equals(normalizedName))
                disjuncts.add(makeDisjunctiveQuery(variant, SynonymIndexFieldNames.VARIANT_NAME));
        }

        disjuncts = disjuncts.stream().filter(Objects::nonNull).collect(Collectors.toList());
        DisjunctionMaxQuery disjunctionMaxQuery = new DisjunctionMaxQuery(disjuncts, 0f);
        BooleanClause fc = null;
        Builder builder = new BooleanQuery.Builder().add(disjunctionMaxQuery, Occur.MUST);
        //new BooleanClause(IntPoint.newExactQuery(SynonymIndexFieldNames.FILTERED, 0), Occur.FILTER);
        if (fc != null)
            builder.add(fc);

        if (!StringUtils.isBlank(key.taxId)) {
            builder.add(new TermQuery(new Term(SynonymIndexFieldNames.TAX_ID_FIELD, key.taxId)), Occur.FILTER).build();
        }
        return builder.build();
    }

    private static String applySpellingCorrection(String name, SpellChecker spellingChecker) throws IOException {
        if (spellingChecker == null)
            return name;
        if (name.trim().isEmpty())
            return null;
        String[] split = name.split("\\s+");
        List<String> newName = new ArrayList<>();
        for (int i = 0; i < split.length; i++) {
            String token = split[i];
            // We only use this function for plural --> singular transform right
            // now
            if (token.length() > 2 && token.endsWith("s")) {
                if (!spellingChecker.exist(token)) {
                    String[] suggestions = spellingChecker.suggestSimilar(token, 5);
                    if (suggestions.length > 0) {
                        newName.add(suggestions[0]);
                        // System.out.println(token + " --> " + suggestions[0]);
                    } else {
                        newName.add(token);
                    }
                } else {
                    newName.add(token);
                }
            } else {
                newName.add(token);
            }
        }
        return newName.stream().collect(Collectors.joining(" "));
    }

}
