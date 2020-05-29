package de.julielab.jules.ae.genemapping.resources;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.julielab.java.utilities.FileUtilities;
import de.julielab.jules.ae.genemapping.CandidateFilter;
import de.julielab.jules.ae.genemapping.utils.norm.TermNormalizer;

/**
 * Takes a gene dictionary and outputs a smaller dictionary containing only
 * entries that do not match a set of negative patterns.
 *
 * @author faessler
 */
public class DictionaryGeneralFilter {

    private static final Logger log = LoggerFactory.getLogger(DictionaryGeneralFilter.class);

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err
                    .println("Usage: " + DictionaryGeneralFilter.class.getSimpleName() + " <input file> <output file>");
            System.exit(1);
        }
        File input = new File(args[0]);
        File output = new File(args[1]);

        DictionaryGeneralFilter filter = new DictionaryGeneralFilter();
        log.info("Filtering dictionary at {} and writing filtered dictionary to {}.", input, output);
        filter.filter(input, output);
        log.info("Dictionary filtering done.");
    }

    private void filter(File input, File output) {
        TermNormalizer termNormalizer = new TermNormalizer();

        try (BufferedReader br = FileUtilities.getReaderFromFile(input); BufferedWriter bw = FileUtilities.getWriterToFile(output)) {
            br.lines()
                    .parallel()
                    .filter(line -> {
                        final String[] record = line.split("\\t");
                        // If the third column is -1, this indicates the source of the name is the official
                        // gene symbol. We want to keep it, no matter what.
                        if (record[2].equals("-1"))
                            return true;
                        String geneName = record[0];
                        String normalizedName = termNormalizer.normalize(geneName);
                        String[] split = normalizedName.split("\\s+");
                        int numDeletedTokens = 0;
                        for (int i = 0; i < split.length; i++) {
                            String token = split[i];
                            boolean delete = false;
                            if (termNormalizer.isNonDescriptive(token))
                                delete = true;
                            if (token.matches("[0-9]+"))
                                delete = true;
                            if (token.matches(CandidateFilter.GREEK_REGEX))
                                delete = true;
                            if (token.matches(CandidateFilter.LAT_NUM_REGEX))
                                delete = true;
                            if (delete)
                                ++numDeletedTokens;
                        }
                        return numDeletedTokens < split.length;
                    })
                    .forEach(line -> {
                        try {
                            synchronized (bw) {
                                bw.write(line);
                                bw.newLine();
                            }
                        } catch (IOException e) {
                            log.error("Could not write filtered dictionary line", e);
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
