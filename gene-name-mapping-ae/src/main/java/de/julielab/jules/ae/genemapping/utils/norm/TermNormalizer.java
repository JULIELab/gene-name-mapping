/**
 * TermNormalizer.java
 * <p>
 * Copyright (c) 2006, JULIE Lab.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * <p>
 * Author: tomanek
 * <p>
 * Current version: 1.5.1
 * Since version:   1.0
 * <p>
 * Creation date: Dec 1, 2006
 * <p>
 * Used by GeneMapper to normalize the query terms before searching
 * for them.
 * <p>
 * Fundamentally changed since 1.1: token splitting rule!
 **/
package de.julielab.jules.ae.genemapping.utils.norm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.lahodiuk.ahocorasick.AhoCorasickOptimized;
import de.julielab.jules.ae.genemapping.AhoCorasickLongestMatchCallback;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;
import org.tartarus.snowball.SnowballProgram;

import de.julielab.jules.ae.genemapping.CandidateFilter;

import static java.util.stream.Collectors.joining;

public class TermNormalizer {

    private final String NON_DESCRIPTIVES_FILE = "/non_descriptives";

    private final String NUMBERPATTERN = "([A-Za-z]+)([0-9]+)";

    private final String SHORTFORMPATTERN = "((.*[0-9a-z]+)(L|R)|(.*[0-9]+)(l|r)|(r|l|R|L))";

    private final String SHORTFORMEND_WITH_NUMBER_PATTERN = "(.* )(ra|rb|rg|bp)( [0-9]*)?";

    private final String SHORTFORMEND_NO_NUMBER_PATTERN = "(.* )(a|b)";

    private final String TOKENSPLITPATTERN = "(.*[a-z])([A-Z0-9].*)|(.*[A-Z])([0-9].*)|(.*[0-9])([a-zA-Z].*)|(.*[A-Z][A-Z])([a-z].*)";

    private final String DOTREMOVAL = "(.*)([a-zA-Z])\\.([a-zA-Z0-9])(.*)";

    private TreeSet<String> nonDescriptives;

    private TreeSet<String> stopwords;

    private HashMap<String, String> plurals;

    private Pattern numberPattern;

    private Pattern shortFormPattern;

    private Pattern shortFormEndWithNumberPattern;

    private Pattern shortFormEndNoNumberPattern;

    private Pattern tokenSplitPattern;

    private Pattern dotRemovalPattern;

    private AhoCorasickOptimized greekAC;

    private SnowballProgram stemmer;

    public TermNormalizer() {

        numberPattern = Pattern.compile(NUMBERPATTERN);
        shortFormPattern = Pattern.compile(SHORTFORMPATTERN);
        tokenSplitPattern = Pattern.compile(TOKENSPLITPATTERN);
        dotRemovalPattern = Pattern.compile(DOTREMOVAL);
        shortFormEndWithNumberPattern = Pattern.compile(SHORTFORMEND_WITH_NUMBER_PATTERN);
        shortFormEndNoNumberPattern = Pattern.compile(SHORTFORMEND_NO_NUMBER_PATTERN);
        final List<String> patterns = Arrays.stream(CandidateFilter.GREEK).collect(Collectors.toList());
        patterns.add("high");
        patterns.add("low");
        greekAC = new AhoCorasickOptimized(patterns);
        initStopwords();
        // initPlurals();
        initNonDescriptives();

        try {
            Class<?> stemClass = Class.forName("org.tartarus.snowball.ext.EnglishStemmer");
            stemmer = (SnowballProgram) stemClass.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e.toString());
        }
    }

    /**
     * run the term normalizer on a file to be normalized (biothesaurus?)
     *
     * @param args
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 2) {
            File unnormalizedFile = new File(args[0]);
            File outputFile = new File(args[1]);
            (new TermNormalizer()).normalizeFile(unnormalizedFile, outputFile);

        } else {
            System.err.println("usage:\nTermNormalizer <inputFil> <outputFile>");
            System.exit(-1);
        }
    }

    /**
     * normalize a single synonym
     *
     * @param term
     * @return
     */
    public String normalize(String term) {

        ArrayList<String> termOld;
        ArrayList<String> newTerm = removeStopwords(term);

        newTerm = removeSpecialCharacters(newTerm);

        do { // apply till there are no more changes
            termOld = newTerm;// .hashCode();
            newTerm = splitAwayNumbers(newTerm);
            // newTerm = replaceShortForms(newTerm);
            newTerm = specialTokenSplit(newTerm);
            // newTerm = transformPlurals(newTerm);
            // } while (newTerm.hashCode() != termOld);
        } while (!newTerm.equals(termOld));

        newTerm = splitAwayCharacterStrings(newTerm);
        newTerm = replaceRomanNumbers(newTerm);
        newTerm = toLowerCase(newTerm);
        // newTerm = replaceKnownAcronyms(newTerm);
        // TODO: apply (porter) stemmer or morphosaurus

        term = ArrayList2String(newTerm);


        // term = replaceShortFormsAtEnd(term);
        // Commented out by EF on March, 11 2019 because the "homolog" term actually made a difference in one case, so being more conservative here.
        //term = removeNonDescriptives(term);

        term = term.trim();

        /*
         * // Ananiadou-style morph. Normalization term = term.replaceAll("\\-",
         * " ");
         *
         * term = ArrayList2String(newTerm); term = toLowerCase(term); term =
         * term.trim();
         */
        return term;
    }

    public List<String> generateVariants(String term) {
        List<String> ret = new ArrayList<>();
        String variant = term.replaceAll("([^-0-9])\\-([^0-9])", "$1$2");
        ret.add(variant);
        variant = splitAwayRomanNumbers(Arrays.asList(term.split("\\s+"))).stream().collect(joining(" "));
        ret.add(variant);
        variant = term.replaceAll("alpha", "a");
        variant = variant.replaceAll("beta", "b");
        variant = variant.replaceAll("gamma", "g");
        variant = variant.replaceAll("delta", "d");
        ret.add(variant);
        variant = term.replaceAll("\\s?alpha", "a");
        variant = variant.replaceAll("\\s?beta", "b");
        variant = variant.replaceAll("\\s?gamma", "g");
        variant = variant.replaceAll("\\s?delta", "d");
        ret.add(variant);
        return ret;
    }

    /**
     * Splits the input term at white spaces, stems the resulting tokens and
     * joins the stemmed tokens with white spaces again.
     *
     * @param normalizedTerm
     * @return
     * @throws IOException
     */
    public String stemNameTokens(String normalizedTerm) throws IOException {
        String[] split = normalizedTerm.split("\\s+");
        List<String> stemmedTokens = new ArrayList<>(split.length);
        for (String token : split) {
            stemmer.setCurrent(token);
            stemmer.stem();
            stemmedTokens.add(stemmer.getCurrent());
        }
        return StringUtils.join(stemmedTokens, " ");
    }

    /**
     * normalize all synonyms in a file (biothesaurus) where the first column is
     * the synonym and the second column is id. all other columns are ignored.
     * columns have to be tab-separated.
     *
     * @param inputFile  the input file (biothesaurus)
     * @param outputFile output file for normalized synonyms
     */
    public void normalizeFile(File inputFile, File outputFile) {

        System.out.println("Normalizing file " + inputFile.getAbsolutePath() + " and writing the result to " + outputFile.getAbsolutePath());
        final AtomicInteger ignoredLines = new AtomicInteger(0);
        try (BufferedReader br = new BufferedReader(new FileReader(inputFile)); FileWriter fileOut = new FileWriter(outputFile)) {
            br.lines()
                    .parallel()
                    .map(line -> line.split("\t"))
                    .filter(split -> {
                        if (split.length != 3) {
                            ignoredLines.incrementAndGet();
                            System.err.println("wrong line format, ignoring line: " + Arrays.toString(split));
                            return false;
                        }
                        return true;
                    })
                    .flatMap(split -> {
                        final Stream.Builder<String> toWrite = Stream.builder();
                        String normalizedSyn = normalize(split[0]);
                        if (!normalizedSyn.isEmpty()) {
                            List<String> variantString = generateVariants(split[0]);
                            for (int i = 0; i < variantString.size(); ++i)
                                variantString.set(i, normalize(variantString.get(i)));
                            toWrite.accept(normalizedSyn + "\t" + split[1] + "\t" + split[2] + "\n");
                            for (int i = 0; i < variantString.size(); ++i)
                                toWrite.accept(variantString.get(i) + "\t" + split[1] + "\t" + split[2] + "\n");
                        }
                        return toWrite.build();
                    })
                    .unordered()
                    .distinct()
                    .forEach(line -> {
                try {
                    synchronized (fileOut) {
                        fileOut.write(line);
                    }
                } catch (IOException e) {
                    System.err.println("Could not write line: " + line);
                    e.printStackTrace();
                }
            });

        } catch (IOException io) {
            io.printStackTrace();
        }

        System.out.println("\n\n\ndone");
        System.out.println("number of ignored lines (due to wrong format): " + ignoredLines);
    }

    /**
     * inserts whitespaces at the following positions: [a-z]->[A-Z0-9]
     * [A-z]->[0-9] [0-9]->[a-zA-Z]
     *
     * @param newTerm
     * @return
     */
    private ArrayList<String> specialTokenSplit(ArrayList<String> newTerm) {
        for (int i = 0; i < newTerm.size(); ++i) {
            String myTerm = newTerm.get(i);
            do {
                newTerm.remove(i);
                newTerm.add(i, myTerm);
                Matcher m = tokenSplitPattern.matcher(myTerm);
                if (m.matches()) {
                    if (m.group(1) != null && m.group(2) != null) {
                        myTerm = m.group(1) + " " + m.group(2);
                    } else if (m.group(3) != null && m.group(4) != null) {
                        myTerm = m.group(3) + " " + m.group(4);
                    } else if (m.group(5) != null && m.group(6) != null) {
                        myTerm = m.group(5) + " " + m.group(6);
                    } else if (m.group(7) != null && m.group(8) != null) {
                        myTerm = m.group(7) + " " + m.group(8);
                    }

                }
            } while (!myTerm.equals(newTerm.get(i)));
        }

        ArrayList<String> finalTerms = new ArrayList<String>();
        for (String token : newTerm) {
            if (token.length() > 0) {
                String[] values = token.split(" ");
                for (int i = 0; i < values.length; i++) {
                    finalTerms.add(values[i]);
                }
            }
        }
        return finalTerms;
    }

    /**
     * split away any character strings (e.g. "alpha" or "low") if proceeded by
     * anything else
     *
     * @param term
     * @return
     */
    private ArrayList<String> splitAwayCharacterStrings(ArrayList<String> term) {
        final AhoCorasickLongestMatchCallback callback = new AhoCorasickLongestMatchCallback();
        for (int i = 0; i < term.size(); i++) {
            callback.clear();
            // We search for the some words (high, low, greek alphabet elements) and don't want to be case-sensitive, so lowercase
            final String currentPart = term.get(i).toLowerCase();
            greekAC.match(currentPart, callback);
            // Make sure that we did not just find the exact dictionary entry but some longer string
            final TreeMap<Range<Integer>, String> longestMatches = callback.getLongestMatches();
            if (!longestMatches.isEmpty() && !(longestMatches.size() == 1 && longestMatches.firstEntry().getValue().equals(currentPart))) {
                int currentPos = 0;
                for (Range<Integer> match : longestMatches.keySet()) {
                    Range<Integer> textBeforeMatch = Range.between(currentPos, match.getMinimum());
                    // The first new token replaced the currentToken
                    if (currentPos == 0) {
                        if (textBeforeMatch.getMaximum() > 0) {
                            term.set(i, currentPart.substring(textBeforeMatch.getMinimum(), textBeforeMatch.getMaximum()));
                            ++i;
                            term.add(i, longestMatches.get(match));
                            ++i;
                        } else {
                            term.set(i, longestMatches.get(match));
                            ++i;
                        }
                    } else {
                        // All other tokens are added to the list
                        if (textBeforeMatch.getMaximum() > textBeforeMatch.getMinimum()) {
                            term.add(i, currentPart.substring(textBeforeMatch.getMinimum(), textBeforeMatch.getMaximum()));
                            ++i;
                        }
                        term.add(i, longestMatches.get(match));
                        ++i;
                    }
                    currentPos = match.getMaximum() + 1;
                }
                if (currentPos < currentPart.length() - 1)
                    term.add(i, currentPart.substring(currentPos));

            }
        }
        return term;
    }

    /**
     * splits away short forms for ligand or receptor (others to come) and
     * replace them by their full form. replacements are allowed iff either "L"
     * or "R" follow lower-case letters or numbers or if "l" or "r" follow
     * upper-case letters or numbers or if "r", "R", "l" or "L" are single
     * tokens
     *
     * @param term
     * @return
     */
    private ArrayList<String> replaceShortForms(ArrayList<String> term) {
        for (int i = 0; i < term.size(); i++) {
            Matcher m = shortFormPattern.matcher(term.get(i));
            if (m.matches()) {
                String base = "";
                String substitute = "";
                // upper-case receptor or ligand
                if (m.group(3) != null) {
                    base = m.group(2);
                    substitute = m.group(3);
                    if (substitute.equals("L")) {
                        substitute = "ligand";
                    } else if (substitute.equals("R")) {
                        substitute = "receptor";
                    }

                } else if (m.group(5) != null) {
                    base = m.group(4);
                    substitute = m.group(5);
                    if (substitute.equals("l")) {
                        substitute = "ligand";
                    } else if (substitute.equals("r")) {
                        substitute = "receptor";
                    }

                } else if (m.group(6) != null) {
                    if (m.group(1).toLowerCase().equals("l")) {
                        substitute = "ligand";
                    } else if (m.group(1).toLowerCase().equals("r")) {
                        substitute = "receptor";
                    }
                }

                term.set(i, base);
                ++i;
                term.add(i, substitute);
            }
        }
        return term;
    }

    /**
     * replaces short forms at the end of a synonym! this function should be
     * applied only after token split, replaceShortForms, toLowerCase, trim etc.
     *
     * @param term
     * @return
     */
    private String replaceShortFormsAtEnd(String term) {
        String replacement = "";
        Matcher m = shortFormEndWithNumberPattern.matcher(term);
        if (m.matches()) {
            if (m.group(2).equals("ra")) {
                replacement = "receptor alpha";
            } else if (m.group(2).equals("rb")) {
                replacement = "receptor beta";
            } else if (m.group(2).equals("rg")) {
                replacement = "receptor gamma";
            } else if (m.group(2).equals("bp")) {
                replacement = "binding protein";
            } else if (m.group(2).equals("a")) {
                replacement = "alpha";
            } else if (m.group(2).equals("b")) {
                replacement = "beta";
            }
            if (replacement.length() > 0) {
                String number = "";
                if (m.group(3) != null) {
                    number = m.group(3);
                }
                return m.group(1) + replacement + number;
            }
        }

        m = shortFormEndNoNumberPattern.matcher(term);
        if (m.matches()) {
            if (m.group(2).equals("a")) {
                replacement = "alpha";
            } else if (m.group(2).equals("b")) {
                replacement = "beta";
            }
            if (replacement.length() > 0) {
                return m.group(1) + replacement;
            }
        }
        return term;
    }

    /**
     * replace other known acronyms by their full forms. Currently only a rule
     * for: IL -> interleukin
     *
     * @param term
     * @return
     */
    private ArrayList<String> replaceKnownAcronyms(ArrayList<String> term) {
        for (int i = 0; i < term.size(); i++) {
            if (term.get(i).equals("il") || term.get(i).equals("IL")) {
                term.set(i, "interleukin");
            }
        }
        return term;
    }

    /**
     * at transitions from a sequence of letters to (a sequence of) numbers we
     * split away the numbers
     *
     * @param term
     * @return
     */
    private ArrayList<String> splitAwayNumbers(ArrayList<String> term) {
        for (int i = 0; i < term.size(); ++i) {
            Matcher m = numberPattern.matcher(term.get(i));
            if (m.matches()) {
                term.set(i, m.group(1));
                ++i;
                term.add(i, m.group(2));
            }
        }
        return term;
    }

    public List<String> splitAwayRomanNumbers(List<String> term) {
        List<String> ret = new ArrayList<>(term);
        for (int i = 0; i < ret.size(); ++i) {
            String token = ret.get(i);
            Matcher romNumMatcher = Pattern.compile(CandidateFilter.LAT_NUM_REGEX).matcher(token);
            while (romNumMatcher.find()) {
                // check if the match was at the end of the term
                if (romNumMatcher.start() != 0 && romNumMatcher.end() == token.length()) {
                    ret.set(i, token.substring(0, romNumMatcher.start()));
                    ++i;
                    ret.add(i, romNumMatcher.group());
                }
            }
        }
        return ret;
    }

    /**
     * replaces roman by greek numbers only if synonym contains more than one
     * token and if roman number is in capital letterns
     *
     * @param synonym
     * @return
     */
    private ArrayList<String> replaceRomanNumbers(ArrayList<String> synonym) {
        if (synonym.size() > 1) {
            for (int i = 0; i < synonym.size(); ++i) {
                String token = synonym.get(i);
                if (token.equals("I")) {
                    synonym.set(i, "1");
                } else if (token.equals("II")) {
                    synonym.set(i, "2");
                } else if (token.equals("III")) {
                    synonym.set(i, "3");
                } else if (token.equals("IV")) {
                    synonym.set(i, "4");
                }

            }
        }
        return synonym;
    }

    /**
     * transforms some known plural forms into their short forms
     *
     * @param term
     * @return
     */
    private ArrayList<String> transformPlurals(ArrayList<String> term) {
        for (int i = 0; i < term.size(); i++) {
            if (plurals.containsKey(term.get(i))) {
                term.set(i, plurals.get(term.get(i)));
            }
        }
        return term;
    }

    private ArrayList<String> toLowerCase(ArrayList<String> term) {
        for (int i = 0; i < term.size(); i++) {
            String s = term.get(i);
            term.set(i, s.trim().toLowerCase());
        }
        return term;
    }

    /**
     * replaces the following special characters by white space: - any non-word
     * - except "." if between two numbers
     *
     * @param term
     * @return
     */
    private ArrayList<String> removeSpecialCharacters(ArrayList<String> term) {
        ArrayList<String> newTerm = new ArrayList<String>();
        for (String token : term) {
            token = token.replaceAll("[\\W_&&[^\\.]]", " ");
            // token = token.replaceAll("[_]", " ");
            // token = token.replaceAll("[^\\.^\\w]", " ");
            Matcher m = dotRemovalPattern.matcher(token);
            if (m.matches()) {
                token = m.replaceFirst(m.group(1) + m.group(2) + " " + m.group(3) + m.group(4));
            }
            token = token.replaceAll("[ ]+", " ");
            token = token.trim();
            if (token.length() > 0) {
                String[] values = token.split(" ");
                for (int i = 0; i < values.length; i++) {
                    newTerm.add(values[i]);
                }
            }
        }
        return newTerm;
    }

    /**
     * replaces dots and hyphens by white space: replication of Tsuruoka et al
     * (2007) normalization
     *
     * @param term
     * @return
     */
    private ArrayList<String> removeDotAndHyphen(ArrayList<String> term) {
        ArrayList<String> newTerm = new ArrayList<String>();
        for (String token : term) {

            token = token.replaceAll("\\-", " ");

            newTerm.add(token);

        }
        return newTerm;
    }

    /**
     * remove stopwords as defined in treeset stopwords only if whole token
     * equals the stopword
     *
     * @param term
     * @return
     */
    private ArrayList<String> removeStopwords(String term) {
        String[] tokens = term.split(" ");
        ArrayList<String> newTerm = new ArrayList<String>(tokens.length);

        // this can actually happen if entity was 'for'
        // (ferredoxin oxidoreductase) See stopwords!
        if (tokens.length == 1) {
            newTerm.add(tokens[0]);
            return newTerm;
        } else {
            for (int i = 0; i < tokens.length; ++i) {
                if (!stopwords.contains(tokens[i])) {
                    newTerm.add(tokens[i]);
                }
            }
        }
        return newTerm;
    }

    public String removeNonDescriptives(String term) {
        String[] tokens = term.split(" ");
        ArrayList<String> newTerm = new ArrayList<String>(tokens.length);

        for (int i = 0; i < tokens.length; ++i) {
            if (!nonDescriptives.contains(tokens[i])) {
                newTerm.add(tokens[i]);
            }
        }
        return ArrayList2String(newTerm);
    }

    public boolean isNonDescriptive(String term) {
        return nonDescriptives.contains(term);
    }

    private void initStopwords() {
        stopwords = new TreeSet<String>();
        stopwords.add("of");
        stopwords.add("for");
        stopwords.add("and");
        stopwords.add("or");
        stopwords.add("the");
        // TODO: remove (un-)defined articles; check POS tag!
    }

    private void initPlurals() {
        plurals = new HashMap<String, String>();
        plurals.put("receptors", "receptor");
        plurals.put("proteins", "protein");
        plurals.put("factors", "factor");
        plurals.put("ligands", "ligand");
        plurals.put("chains", "chain");
        plurals.put("antigens", "antigen");
        plurals.put("genes", "gene");
        plurals.put("transcripts", "transcript");
    }

    private void initNonDescriptives() {
        nonDescriptives = new TreeSet<String>();

        InputStream in = this.getClass().getResourceAsStream(NON_DESCRIPTIVES_FILE);
        InputStreamReader isr = new InputStreamReader(in);
        BufferedReader nonDescReader = new BufferedReader(isr);

        try {

            String line = "";
            while ((line = nonDescReader.readLine()) != null) {
                nonDescriptives.add(line.trim());
            }
            nonDescReader.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String ArrayList2String(ArrayList<String> term) {
        StringBuffer transform = new StringBuffer("");
        for (int i = 0; i < term.size(); ++i) {
            transform.append(term.get(i) + " ");
        }
        // There is an entry named '-' in the biothesaurus!
        if (transform.length() != 0) {
            transform.deleteCharAt(transform.length() - 1);
        }
        return transform.toString().trim();
    }

}