package de.julielab.jules.ae.genemapping.resources;

import de.julielab.java.utilities.FileUtilities;
import de.julielab.jules.ae.genemapping.CandidateFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 * Removes gene synonyms from the given dictionary that look like gene families or gene domains or are otherwise
 * unspecific.This is yet another dictionary cleaning algorithm besied {@link DictionaryGeneralFilter} and the
 * <tt>DictCleaner.pl</tt> script in the <tt>update_resources_and_indices</tt> directory.
 * </p>
 */
public class DictionaryFamilyDomainFilter {
    private final static Logger log = LoggerFactory.getLogger(DictionaryFamilyDomainFilter.class);

    /*
     * defines the maximum length of synonyms to be considered longer synonyms are
     * omitted, i.e. not stored in the index
     */
    private static final int MAX_SYNLENGTH = 8;
    private static final int MIN_SYNLENGTH = 2;

    public static void main(String args[]) {
        if (args.length != 2) {
            System.err.println("Usage: " + DictionaryGeneralFilter.class.getSimpleName() + " <input dictionary> <output dictionary>");
            System.exit(1);
        }
        File input = new File(args[0]);
        File output = new File(args[1]);

        final ExecutorService executorService = Executors.newFixedThreadPool(20);
        try (final BufferedReader br = FileUtilities.getReaderFromFile(input); final BufferedWriter bw = FileUtilities.getWriterToFile(output)) {
            cleanDictionary(input, output, executorService, br, bw);
            waitFinishAndShutdownExecutor(executorService);
        } catch (IOException e) {
            log.error("Could not read or write", e);
            waitFinishAndShutdownExecutor(executorService);
        }
    }

    private static void waitFinishAndShutdownExecutor(ExecutorService executorService) {
        try {
            log.info("Waiting 5 minutes for the termination of all threads.");
            executorService.shutdown();
            executorService.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            log.warn("The wait for threads to finish was interrupted.");
        } finally {
            log.info("Shutting down the executor.");
            executorService.shutdownNow();
        }
    }

    private static void cleanDictionary(File input, File output, ExecutorService executorService, BufferedReader br, BufferedWriter bw) throws IOException {
        log.info("Reading dictionary from {} and writing the filtered output to {}", input, output);
        CandidateFilter cf = new CandidateFilter();

        final Iterator<String> lineIt = br.lines().iterator();
        while (lineIt.hasNext()) {
            String line = lineIt.next();
            executorService.submit(() -> {
                try {
                    processLine(cf, bw, line);
                } catch (IOException e) {
                    log.error("Could not write to the filtered dictionary", e);
                }
            });
        }

    }

    /**
     * Checks if the line should be filtered out. If not, it is written to the output.
     *
     * @param cf   A CandidateFilter instance.
     * @param bw   The output writer.
     * @param line The gene record to check.
     * @throws IOException If writing goes wrong.
     */
    private static void processLine(CandidateFilter cf, BufferedWriter bw, String line) throws IOException {
        final String[] split = line.split("\t");
        if (split.length != 3)
            System.err.println("ERR: normalized dictionary not in expected format. \ncritical line: " + line);

        String id = split[1];
        String synonym = split[0];
        Integer priority = Integer.parseInt(split[2]);

        // If the synonym is the official gene symbol, we accept it, no matter what
        if (priority != -1 && isFiltered(cf, synonym))
            return;

        synchronized (bw) {
            bw.write(line);
            bw.newLine();
        }
    }

    /**
     * Checks the synonym for length and token number and if it looks
     * as though it wouldn't designate a concrete gene but rather a family or domain.
     *
     * @param cf      A CandidateFilter instance.
     * @param synonym The synonym under scrutiny.
     * @return <tt>true</tt> if the synonym should be removed from the dictionary, <tt>false</tt> otherwise.
     */
    public static boolean isFiltered(CandidateFilter cf, String synonym) {
        boolean filtered = false;
        // ignore synonyms smaller than MIN_SYNLENGTH or longer than
        // MAX_SYNLENGTH
        int synTokenNum = synonym.split(" ").length;
        if (synTokenNum > MAX_SYNLENGTH
                || (synTokenNum < MIN_SYNLENGTH && synonym.length() < MIN_SYNLENGTH)) {
            log.debug("Removed due to illegal length (too short or too long): {}", synonym);
            filtered = true;
        }

        if (!filtered && checkNoGeneDesignation(cf, synonym)) filtered = true;
        return filtered;
    }

    /**
     * Checks the given name for occurrences of words that indicate a gene family, a domain or another kind
     * of un(der)specified gene which would actually hurt gene normalization performance.
     *
     * @param cf
     * @param normalizedName
     * @return
     */
    private static boolean checkNoGeneDesignation(CandidateFilter cf, String normalizedName) {
        boolean filtered = false;
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
        return filtered;
    }
}
