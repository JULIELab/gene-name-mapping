package de.julielab.jules.ae.genemapping.resources;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.julielab.java.utilities.FileUtilities;
import de.julielab.jules.ae.genemapping.resources.ncbigene.GeneXMLUtils;
import de.julielab.jules.ae.genemapping.utils.ReaderInputStream;

/**
 * <p>
 * Alternative to {@link GeneXMLFromASN1Extractor}.
 * </p>
 * <p>
 * Downloads NCBI Gene XML from NCBI via eUtils. The class extracts the protein
 * names for each gene and its summary. The downloaded XML may be stored for
 * later use in which case a new download is not required.
 * </p>
 * <p>
 * Note that the download is a fragile way to get the required gene information
 * in contrast to {@link GeneXMLFromASN1Extractor}. During very long downloads,
 * network issues might corrupt the downloaded data. Also, the download takes a
 * lot of time. It is still feasible for 10-20 taxonomy IDs.
 * </p>
 *
 * @author faessler
 */
public class GeneXMLDownloader {

    private static final String TOOL_NAME = "JulieLabGeneXMLDownloader";

    private static final Logger log = LoggerFactory.getLogger(GeneXMLDownloader.class);

    public static String EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/";

    // eUtils rules state not to send more than 3 requests per second. We will
    // control this using the CountDownLatch.
    // private static CountDownLatch requestsPerSecondRestriction;

    // private static Thread downloadRestrictionThread;
    private static DownloadRestricter downloadRestricter;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: " + GeneXMLDownloader.class.getSimpleName()
                    + " <taxonomy ids file> <extracted information storage directory> <eMail address for eUtils> [gene_info file] [XML directory]");
            System.err.println(
                    "If XML directory is given but empty, XML files with the downloaded batches will be stored there for later use. If the files already exist, no download will happen but the existing files will be read.");
            System.err.println(
                    "If the gene_info file is given, its creation date will be compared to the oldest XML file in the XML directory, if not empty. If the gene_info file is newer than the existing XML files, the files will be refreshed by downloading them.");
            System.exit(0);
        }

        File taxIdFile = new File(args[0]);
        File storageDirectory = new File(args[1]);
        String eMail = args[2];
        File gene_info = args.length > 3 && !StringUtils.isBlank(args[3]) ? new File(args[3]) : null;
        File xmlDirectory = args.length > 4 && !StringUtils.isBlank(args[4]) ? new File(args[4]) : null;

        if (!(eMail.contains("@") && eMail.contains(".")))
            throw new IllegalArgumentException(eMail
                    + " does not appear to be a valid eMail address. Please provide a valid eMail address for the eUtils. NCBI would like to be able to contact you for the case that the script goes wild which should not be the case if you don't alter it.");

        log.info("Taxonomy ID file: {}", taxIdFile);
        log.info("Storage directory for created resource files: {}", storageDirectory);
        log.info("gene_info file to compare timestamp to (optional): {}", gene_info);
        log.info("XML directory to store/read XML from to/from (optional): {}", xmlDirectory);

        List<File> geneXmlDownloaderFiles = GeneXMLUtils.getMetaFiles(storageDirectory);
        File summariesFile = geneXmlDownloaderFiles.get(0);
        File proteinNamesFile = geneXmlDownloaderFiles.get(1);
        File downloadedTaxIdsFile = geneXmlDownloaderFiles.get(2);
        File refseqStatusFile = geneXmlDownloaderFiles.get(3);

        if (!storageDirectory.exists()) {
            log.info("Creating directory {}", storageDirectory);
            storageDirectory.mkdirs();
        }

        // Check if we need to download anything at all
        boolean dbFileIsNewer = gene_info.exists()
                ? downloadedTaxIdsFile.lastModified() < gene_info.lastModified() : true;
        Set<String> notYetDownloadedTaxIds = GeneXMLUtils.determineMissingTaxIds(taxIdFile, storageDirectory, gene_info,
                downloadedTaxIdsFile);

        // If there is already something downloaded, we need to merge the
        // already existing information and the new.
        // This is the 1. step: move the original files to a temporary location
        // so we can create the files anew.
        moveMetaFilesToTemporaryLocation(downloadedTaxIdsFile, geneXmlDownloaderFiles, dbFileIsNewer);

        // set concurrency level
        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "5");

        try (OutputStream osSummaries = FileUtilities.getOutputStreamToFile(summariesFile);
             OutputStream osProtnames = FileUtilities.getOutputStreamToFile(proteinNamesFile);
             OutputStream osTaxIds = FileUtilities.getOutputStreamToFile(downloadedTaxIdsFile);
             OutputStream osRefSeqStatus = FileUtilities.getOutputStreamToFile(refseqStatusFile)) {

            log.info("Writing summaries into file {}", summariesFile);
            log.info("Writing protein names into file {}", proteinNamesFile);
            log.info("Writing taxonomy IDs were gene meta information has been downloaded for into file {}",
                    downloadedTaxIdsFile);


            // now download the new data
            notYetDownloadedTaxIds.parallelStream().forEach(taxId -> {
                        log.debug("Processing taxonomy ID {}", taxId);
                        try {
                            boolean geneInfoNewerThanXmlFiles = isGeneInfoNewerThanXmlFiles(xmlDirectory, taxId, gene_info);
                            // Get whole XML files that might have been downloaded
                            // before. If the directory is null or doesn't exist, the
                            // result array will be empty.
                            File[] xmlFilesInDirectory = getXmlFilesInDirectoryForTaxId(xmlDirectory, taxId);
                            if (xmlDirectory != null)
                                log.info("Found {} XML files for organism with taxonomy ID {} in directory {}",
                                        new Object[]{xmlFilesInDirectory.length, taxId, xmlDirectory});
                            // ------ Decide whether to read from existing XML or do the
                            // download ------
                            if (xmlFilesInDirectory.length == 0 || geneInfoNewerThanXmlFiles) {
                                // We need to download XML data. Set up request
                                // restriction.
                                synchronized (GeneXMLDownloader.class) {
                                    if (null == downloadRestricter) {
                                        downloadRestricter = new DownloadRestricter();
                                        downloadRestricter.start();
                                    }
                                }

                                boolean storeXml = (null != xmlDirectory
                                        && (xmlFilesInDirectory.length == 0 || geneInfoNewerThanXmlFiles));
                                if (geneInfoNewerThanXmlFiles)
                                    clearXmlFilesForTaxId(xmlDirectory, taxId);
                                if (storeXml && !xmlDirectory.exists()) {
                                    log.info("XML directory {} does not exist and is created.", xmlDirectory);
                                    xmlDirectory.mkdirs();
                                }
                                // this request will only give a query key to get the
                                // actual
                                // results
                                // batch wise
                                URL downloadHandleUrl = new URL(EUTILS + "esearch.fcgi?db=gene&retmax=1&usehistory=y&term="
                                        + taxId + "[taxid]+AND+alive[properties]&tool=" + TOOL_NAME + "&email=" + eMail);
                                log.trace("Request for download handle: {}", downloadHandleUrl);
                                downloadRestricter.waitForTicket();
                                InputStream responseStream = downloadHandleUrl.openConnection().getInputStream();
                                log.debug("Contacting E-Utils for download of XML gene information for taxonomy ID {}...",
                                        taxId);
                                DownloadHandle downloadHandle = readDownloadHandleXml(responseStream);

                                log.debug("Got a download handle for a search result of {} entries for taxonomy ID {}",
                                        downloadHandle.count, taxId);

                                if (downloadHandle.count == 0) {
                                    log.debug(
                                            "Did not receive any entries for taxonomy ID {}. This could point to an error or just no available entries. This taxonomy ID is skipped. The request URL was {}",
                                            taxId, downloadHandleUrl);
                                } else {
                                    if (storeXml)
                                        log.info("Downloading Gene XML data to {}. This will take a few hours.", xmlDirectory);
                                    log.info("Gene meta information will be written into directory {}", storageDirectory);

                                    // Now download the gene XML batch-wise. The batches
                                    // must
                                    // not be
                                    // set
                                    // too
                                    // large because then timeouts could occur.
                                    int retmax = 500;
                                    for (int retstart = 0; retstart < downloadHandle.count; retstart += retmax) {
                                        log.debug("Downloading gene XML records for taxonomy ID {}: {}", taxId,
                                                retstart + " - " + Math.min(retstart + retmax - 1, downloadHandle.count));
                                        String format = String.format(
                                                EUTILS + "efetch.fcgi?rettype=xml&retmode=text&retstart=%s&retmax=%s&"
                                                        + "db=gene&query_key=%s&WebEnv=%s&tool=%s&email=%s",
                                                retstart, retmax, downloadHandle.queryKey, downloadHandle.webEnv, TOOL_NAME,
                                                eMail);
                                        log.trace("Request URL: {}", format);
                                        URL batchUrl = new URL(format);

                                        downloadRestricter.waitForTicket();
                                        log.debug(
                                                "Reading stream response and parsing the respective XML (this is the download step and will take a while)");
                                        InputStream is = batchUrl.openStream();
                                        try {
                                            if (storeXml) {
                                                String xml = IOUtils.toString(is, "UTF-8");
                                                try (OutputStream os = new GZIPOutputStream(
                                                        new FileOutputStream(new File(xmlDirectory.getAbsolutePath()
                                                                + File.separator + "genes-taxid" + taxId + "-" + retstart + "-"
                                                                + Math.min(retstart + retmax - 1, downloadHandle.count)
                                                                + ".xml.gz")))) {
                                                    IOUtils.copy(new StringReader(xml), os, "UTF-8");
                                                }
                                                is = new ReaderInputStream(new StringReader(xml), "UTF-8");
                                            }
                                            GeneXMLUtils.extractAndWriteGeneInfoToFile(osSummaries, osProtnames, osRefSeqStatus, is);
                                        } catch (Exception e) {
                                            throw new RuntimeException("Error while getting XML data for taxonomy ID " + taxId
                                                    + " from NCBI eUtils.", e);
                                        }
                                    }
                                }
                            } else {
                                // ------- The "use existing XML files" branch -------
                                log.info("Reading existing gene XML data from {}", xmlDirectory);

                                Stream.of(xmlFilesInDirectory).parallel().map(xmlFile -> {
                                    try {
                                        InputStream is = new GZIPInputStream(new FileInputStream(xmlFile));
                                        log.trace("Reading XML file {}", xmlFile);
                                        return GeneXMLUtils.extractGeneInfoFromXml(is);
                                    } catch (IOException | XMLStreamException e) {
                                        e.printStackTrace();
                                    }
                                    return null;
                                }).forEach(extractList -> {
                                    try {
                                        GeneXMLUtils.writeGeneInfoToFile(extractList, osSummaries, osProtnames, osRefSeqStatus);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                });
                            }
                            log.info("Done extracting gene data from XML and writing result files for taxonomy ID {}.", taxId);
                            IOUtils.write(taxId + "\n", osTaxIds, "UTF-8");
                        } catch (IOException | XMLStreamException e) {
                            e.printStackTrace();
                        }
                    }// loop / stream end
            );
            mergeGeneMetaFiles(storageDirectory, geneXmlDownloaderFiles);
        } finally {
            log.info("Interrupting download restriction thread.");
            // downloadRestrictionThread.interrupt();
            downloadRestricter.interrupt();
            downloadRestricter.join();
            log.info("Extraction of data from Gene XML complete.");
        }
    }

    /**
     * Moves the currently existing gene meta data files into a temporary
     * location by simply renaming the files to "&lt;filename&gt;.tmp.gz".
     *
     * @param downloadedTaxIdsFile      The file of already processed taxonomy IDs.
     * @param geneXmlDownloaderFiles    The list of gene meta files.
     * @param geneInfoNewerThanMetaData Whether the source database file is newer than the gene meta
     *                                  files.
     * @throws IOException If reading/writing goes wrong.
     */
    private static void moveMetaFilesToTemporaryLocation(File downloadedTaxIdsFile, List<File> geneXmlDownloaderFiles,
                                                         boolean geneInfoNewerThanMetaData) throws IOException {
        if (downloadedTaxIdsFile.exists()) {
            for (File f : geneXmlDownloaderFiles) {
                if (!geneInfoNewerThanMetaData && f.exists()) {
                    File tmpFile = new File(f.getAbsolutePath().replaceAll("\\.gz|\\.gzip", "") + "tmp.gz");
                    log.trace("Moving existing file {} to temporary file {}", f, tmpFile);
                    Files.move(f.toPath(), tmpFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    f.delete();
                }
            }
        }
    }

    /**
     * Merges the gene meta files that existed before the current processing and
     * those created by the current process.
     *
     * @param storageDirectory       The directory where the meta files are stored.
     * @param geneXmlDownloaderFiles The gene XML meta data files.
     * @throws IOException If file reading/writing fails.
     */
    private static void mergeGeneMetaFiles(File storageDirectory, List<File> geneXmlDownloaderFiles) throws IOException {
        for (File f : geneXmlDownloaderFiles) {
            File tmpFile = new File(f.getAbsolutePath().replaceAll("\\.gz|\\.gzip", "") + ".tmp.gz");
            if (tmpFile.exists()) {
                File mergeFile = new File(storageDirectory.getAbsolutePath() + File.separator + "merging.tmp.gz");
                log.trace("Merging temporary file {} into main file {}", tmpFile, f);
                try (BufferedWriter writer = FileUtilities.getWriterToFile(mergeFile)) {
                    // First add the contents of the old file
                    try (BufferedReader reader = FileUtilities.getReaderFromFile(tmpFile)) {
                        char[] buffer = new char[2048];
                        int bytesRead = 0;
                        while ((bytesRead = reader.read(buffer)) != -1) {
                            writer.write(buffer, 0, bytesRead);
                        }
                    }
                    // Then add the new file contents
                    try (BufferedReader reader = FileUtilities.getReaderFromFile(f)) {
                        char[] buffer = new char[2048];
                        int bytesRead = 0;
                        while ((bytesRead = reader.read(buffer)) != -1) {
                            writer.write(buffer, 0, bytesRead);
                        }
                    }
                }
                Files.move(mergeFile.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                tmpFile.delete();
            }
        }
    }

    private static void clearXmlFilesForTaxId(File xmlDirectory, String taxId) {
        File[] xmlFilesInDirectory = getXmlFilesInDirectoryForTaxId(xmlDirectory, taxId);
        log.debug("Deleting {} XML files in directory {}", xmlFilesInDirectory.length, xmlDirectory);
        for (File xmlFile : xmlFilesInDirectory)
            xmlFile.delete();
    }

    private static boolean isGeneInfoNewerThanXmlFiles(File xmlDirectory, String taxId, File gene_info) {
        if (xmlDirectory == null || !xmlDirectory.exists() || gene_info == null)
            return false;
        File[] xmlFiles = getXmlFilesInDirectoryForTaxId(xmlDirectory, taxId);
        long latestXmlModification = Long.MAX_VALUE;
        for (File xmlFile : xmlFiles) {
            if (xmlFile.lastModified() < latestXmlModification)
                latestXmlModification = xmlFile.lastModified();
        }
        boolean geneInfoIsNewer = gene_info.lastModified() > latestXmlModification;
        log.debug("gene_info file at {} is {} than the oldest XML file for taxonomy ID {} in {}",
                new Object[]{gene_info, geneInfoIsNewer ? "newer" : "older", taxId, xmlDirectory});
        return geneInfoIsNewer;
    }

    private static File[] getXmlFilesInDirectoryForTaxId(File xmlDirectory, final String taxId) {
        if (xmlDirectory == null || !xmlDirectory.exists())
            return new File[0];
        File[] xmlFiles = xmlDirectory.listFiles(new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().contains("taxid" + taxId) && name.toLowerCase().endsWith("xml.gz");
            }
        });
        if (xmlFiles != null && xmlFiles.length > 0) {
            Arrays.sort(xmlFiles, new Comparator<File>() {

                @Override
                public int compare(File o1, File o2) {
                    // file names have the form
                    // genes-taxidXXXX-from-to.xml.gz,
                    // e.g.
                    // genes-taxid9606-20000-20499.xml.gz
                    Integer start1 = Integer.parseInt(o1.getName().split("-")[2]);
                    Integer start2 = Integer.parseInt(o2.getName().split("-")[2]);
                    return start1.compareTo(start2);
                }
            });
        }
        return xmlFiles == null ? new File[0] : xmlFiles;
    }

    private static DownloadHandle readDownloadHandleXml(InputStream downloadHandleResponse) throws XMLStreamException {
        DownloadHandle downloadHandle = new DownloadHandle();
        ByteArrayOutputStream baos = null;
        try {
            baos = new ByteArrayOutputStream();
            IOUtils.copy(downloadHandleResponse, baos);

            XMLInputFactory factory = XMLInputFactory.newInstance();
            XMLStreamReader parser = factory.createXMLStreamReader(new ByteArrayInputStream(baos.toByteArray()));
            final String countTag = "Count";
            final String queryKeyTag = "QueryKey";
            final String webEnvTag = "WebEnv";
            String currentTag = null;
            while (parser.hasNext()) {

                switch (parser.getEventType()) {

                    case XMLStreamConstants.START_ELEMENT:
                        currentTag = parser.getLocalName();
                        switch (currentTag) {
                            case countTag:
                                // we are only interested in the first occurrence of a
                                // count
                                // element
                                if (downloadHandle.count == 0)
                                    downloadHandle.count = Integer.parseInt(parser.getElementText());
                                break;
                            case queryKeyTag:
                                downloadHandle.queryKey = parser.getElementText();
                                break;
                            case webEnvTag:
                                downloadHandle.webEnv = parser.getElementText();
                                break;
                        }
                        break;
                    case XMLStreamConstants.CHARACTERS:
                        currentTag = null;
                        break;

                    default:
                        break;
                }
                parser.next();
            }
        } catch (XMLStreamException e) {
            log.error("Caught error while trying to parse download handle info", e);
            try {
                log.error("The contents of the download handle input stream were: {}",
                        new String(baos.toByteArray(), "UTF-8"));
            } catch (UnsupportedEncodingException e1) {
                log.error("The contents of the download handle input stream were: {}", new String(baos.toByteArray()));
            }

        } catch (IOException e) {
            log.error(
                    "The download handle input stream could not be copied to a ByteArrayOutputStream (done for error reporting purposes)",
                    e);
        }
        return downloadHandle;
    }

    private static class DownloadHandle {
        int count;
        String queryKey;
        String webEnv;

        @Override
        public String toString() {
            return "DownloadHandle [count=" + count + ", queryKey=" + queryKey + ", webEnv=" + webEnv + "]";
        }
    }

    private static class DownloadRestricter extends Thread {
        /**
         * This is a queue of a given capacity of tickets. A ticket is actually
         * just some placeholder object to indicate that there are more tickets
         * or not. The queue blocking methods take and offer are used to let
         * threads wait if the tickets are empty and to refill the tickets all
         * three seconds.
         */
        private ArrayBlockingQueue<Object> tickets;

        public DownloadRestricter() {
            tickets = new ArrayBlockingQueue<>(3);
        }

        @Override
        public void run() {
            try {
                while (true) {
                    // fill the queue
                    while (tickets.offer(new Object()))
                        ;
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                log.info("Download restricter has finished execution.");
            }
        }

        public void waitForTicket() {
            try {
                log.trace("Awaiting a free request ticket.");
                tickets.take();
                log.trace("Ticket acquired.");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

}
