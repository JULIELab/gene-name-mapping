package de.julielab.jules.ae.genemapping.resources;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.stream.XMLStreamException;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

import de.julielab.java.utilities.CLIInteractionUtilities;
import de.julielab.java.utilities.FileUtilities;
import de.julielab.jules.ae.genemapping.resources.ncbigene.GeneXMLUtils;
import de.julielab.jules.ae.genemapping.resources.util.UncheckedGeneMapperResourcesException;

/**
 * <p>
 * Alternative to {@link GeneXMLDownloader}.
 * </p>
 * <p>
 * Reads ASN.1 files obtainable from
 * ftp://ftp.ncbi.nih.gov/gene/DATA/ASN_BINARY/ and extracts the required gene
 * information for the JCoRe Gene Mapper.
 * </p>
 * <p>
 * This approach is much more robust than downloading everything from the
 * Internet and is most likely also much quicker. However, it relies on an
 * external tool, gene2xml, which is written in C and thus platform dependent.
 * This might cause issues depending on the environment. The available gene2xml
 * programs are located at
 * ftp://ftp.ncbi.nlm.nih.gov/asn1-converters/by_program/gene2xml/. The path to
 * the program to use is given as the last parameter when calling this class.
 * This class was used with the linux64 program.
 * </p>
 *
 * @author faessler
 */
public class GeneXMLFromASN1Extractor {

    private static final Logger log = LoggerFactory.getLogger(GeneXMLFromASN1Extractor.class);

    /**
     * The path to the gene2xml program to use.
     */

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: " + GeneXMLDownloader.class.getSimpleName()
                    + " <taxonomy ids file> <extracted information storage directory> <gzipped ASN1 file, e.g. All_Data.ags.gz> <path to gene2xml executable>");
            System.exit(0);
        }

        File taxIdFile = new File(args[0]);
        File storageDirectory = new File(args[1]);
        File asnFile = new File(args[2]);
        File gene2xml = new File(args[3]);

        log.info("Taxonomy ID file: {}", taxIdFile);
        log.info("Storage directory for created resource files: {}", storageDirectory);
        log.info("ASN.1 file to extract data from: {}", asnFile);
        log.info("gene2xml executable path: {}", gene2xml);

        if (!gene2xml.exists())
            throw new IllegalArgumentException(
                    "The gene2xml executable path " + gene2xml.getAbsolutePath() + " does not exist.");
        else if (!gene2xml.canExecute()) {
            throw new IllegalStateException("The gene2xml executable at " + gene2xml.getAbsolutePath()
                    + " is not allowed to be executed by the current user. Set executable rights (the 'x' flag un *nix systems) and try again.");
        }

        List<File> geneXmlDownloaderFiles = GeneXMLUtils.getMetaFiles(storageDirectory);
        File downloadedTaxIdsFile = geneXmlDownloaderFiles.get(2);

        // Check if the gene meta information cache at the given directory is
        // deprecated.
        boolean dbFileIsNewer = asnFile.exists() && downloadedTaxIdsFile.exists()
                ? downloadedTaxIdsFile.lastModified() < asnFile.lastModified()
                : true;
        if (dbFileIsNewer && downloadedTaxIdsFile.exists()) {
            if (!CLIInteractionUtilities.readYesNoFromStdInWithMessage("The ASN.1 file at " + asnFile.getAbsolutePath()
                    + " is newer than the meta cache files at " + storageDirectory.getAbsolutePath()
                    + ". By continuing, the old cache will completely deleted and built from scratch for the taxonomy IDs given by "
                    + taxIdFile.getAbsolutePath() + ". Do you wish to proceed?", true)) {
                log.info("Aborting due to user wish.");
                System.exit(2);
            }
            FileUtils.deleteDirectory(storageDirectory);
        }
        if (!storageDirectory.exists()) {
            log.info("Creating directory {}", storageDirectory);
            storageDirectory.mkdirs();
        }

        // Determine the tax IDs for which we need to extract gene meta information.
        Set<String> missingTaxIds = GeneXMLUtils.determineMissingTaxIds(taxIdFile, storageDirectory, asnFile,
                downloadedTaxIdsFile);

        if (dbFileIsNewer || !missingTaxIds.isEmpty()) {
            if (!missingTaxIds.isEmpty())
                log.info("There are missing taxonomy IDs for which gene meta information needs to be extracted.");
            if (dbFileIsNewer)
                log.info(
                        "The {} file has been updated and is newer than the existing gene meta information. The old gene meta information cache has been deleted and is now built again.",
                        asnFile);
            log.info("Extracting gene meta information from {}. This will take a few hours.", asnFile);
            extractGeneInfoFromASN1(asnFile, geneXmlDownloaderFiles, taxIdFile, gene2xml);
            log.info("Finished gene meta information extraction.");
        } else {
            log.info(
                    "Gene meta information for all given taxonomy IDs has already been extracted. Nothing to do, exiting.");
        }

    }

    /**
     * This method decompresses the input file and extracts xml data for each of the
     * given organisms.
     *
     * @param inputFile
     * @param geneXmlDownloaderFiles
     * @param requestedTaxIdsFile
     * @param gene2xml
     * @throws IOException
     * @throws XMLStreamException
     */
    private static void extractGeneInfoFromASN1(File inputFile, List<File> geneXmlDownloaderFiles,
                                                File requestedTaxIdsFile, File gene2xml) throws IOException, XMLStreamException {
        // -i - input
        // -c - compressed
        // -b - binary
        Process proc = Runtime.getRuntime().exec(gene2xml.getAbsolutePath() + " -i " + inputFile + " -c -b");
        try (InputStream is = proc.getInputStream();
             OutputStream osSummaries = FileUtilities.getOutputStreamToFile(geneXmlDownloaderFiles.get(0));
             OutputStream osProtnames = FileUtilities.getOutputStreamToFile(geneXmlDownloaderFiles.get(1));
             OutputStream osRefSeqStatus = FileUtilities.getOutputStreamToFile(geneXmlDownloaderFiles.get(3))) {

            GeneXMLUtils.extractAndWriteGeneInfoToFile(osSummaries, osProtnames, osRefSeqStatus, is);
            File alreadyExtractedTaxIdList = geneXmlDownloaderFiles.get(2);

            Set<String> alreadyExtractedTaxIds = alreadyExtractedTaxIdList.exists()
                    ? FileUtilities.getReaderFromFile(alreadyExtractedTaxIdList).lines()
                    .filter(line -> line.trim().length() != 0).collect(Collectors.toSet())
                    : Collections.emptySet();
            Set<String> requestedTaxIds = FileUtils.readLines(requestedTaxIdsFile, "UTF-8").stream()
                    .filter(line -> line.trim().length() != 0).collect(Collectors.toSet());
            try (BufferedWriter bw = FileUtilities.getWriterToFile(alreadyExtractedTaxIdList)) {
                Sets.union(alreadyExtractedTaxIds, requestedTaxIds).stream().forEach(t -> {
                    try {
                        bw.write(t);
                        bw.newLine();
                    } catch (IOException e) {
                        throw new UncheckedGeneMapperResourcesException(e);
                    }
                });
            }
        }
    }
}
