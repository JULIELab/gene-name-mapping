package de.julielab.jules.ae.genemapping.resources.ncbigene;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

import de.julielab.java.utilities.FileUtilities;

public class GeneXMLUtils {

    public static final String TAXIDS_FILENAME = "genexmldownloader.taxids.gz";
    public static final String EG2ENTREZGENE_PROT_FILENAME = "eg2entrezgene_prot-genexmldownloader.gz";
    public static final String EG2SUMMARY_FILENAME = "eg2summary-genexmldownloader.gz";
    private static final String EG2REFSEQ_AND_TRACK_STATUS_FILENAME = "eg2refseq_genetrack_status-genexmldownloader.gz";
    private static final Logger log = LoggerFactory.getLogger(GeneXMLUtils.class);

    public static void writeGeneInfoToFile(List<GeneXmlExtract> geneExtractList, OutputStream osSummaries,
                                           OutputStream osProtnames, OutputStream osRefSeqAndTrackStatus) throws IOException {
        log.trace("Writing gene summaries of current XML batch.");
        synchronized (osSummaries) {
            for (GeneXmlExtract extract : geneExtractList) {
                if (extract.summary != null) {
                    IOUtils.write(extract.geneId + "\t" + extract.summary + "\n", osSummaries, "UTF-8");
                }
            }
        }

        synchronized (osProtnames) {
            log.trace("Writing entrezgene_prot names of current XML batch.");
            for (GeneXmlExtract extract : geneExtractList) {
                if (extract.entrezgeneProt != null) {
                    if (extract.entrezgeneProt.protrefName != null) {
                        for (String protName : extract.entrezgeneProt.protrefName) {
                            IOUtils.write(extract.geneId + "\t" + protName + "\n", osProtnames, "UTF-8");
                        }
                    }
                    if (null != extract.entrezgeneProt.protrefDesc)
                        IOUtils.write(extract.geneId + "\t" + extract.entrezgeneProt.protrefDesc + "\n", osProtnames,
                                "UTF-8");
                }
            }
        }

        synchronized (osRefSeqAndTrackStatus) {
            log.trace("Writing entrez gene RefSeq status entries of current XML batch");
            for (GeneXmlExtract extract : geneExtractList) {
                String refSeqStatus = extract.refSeqStatus != null ? extract.refSeqStatus : "<none given>";
                IOUtils.write(extract.geneId + "\t" + refSeqStatus + "\t" + extract.geneTrackStatusValue + "\t" + extract.geneTrackStatus + "\n", osRefSeqAndTrackStatus, "UTF-8");
            }
        }

    }

    public static List<GeneXmlExtract> extractGeneInfoFromXml(InputStream openStream)
            throws XMLStreamException, IOException {
        List<GeneXmlExtract> geneExtractList = new ArrayList<>();

        XMLInputFactory factory = XMLInputFactory.newInstance();
        XMLStreamReader parser = factory.createXMLStreamReader(openStream);
        String currentTag = null;
        GeneXmlExtract currentXmlExtract = null;
        boolean inEntrezGeneSource = false;
        boolean inTaxonDbtag = false;
        boolean inRefSeqStatusCommentary = false;
        boolean inEntrezgene_comments = false;
        while (parser.hasNext()) {

            switch (parser.getEventType()) {
                case XMLStreamConstants.START_ELEMENT:
                    currentTag = parser.getLocalName();
                    switch (currentTag) {
                        case "Entrezgene":
                            currentXmlExtract = new GeneXmlExtract();
                            break;
                        case "Entrezgene_prot":
                            currentXmlExtract.entrezgeneProt = readEntrezgeneProtFromXml(parser);
                            break;
                        case "Gene-track_geneid":
                            currentXmlExtract.geneId = parser.getElementText();
                            break;
                        case "Entrezgene_summary":
                            currentXmlExtract.summary = parser.getElementText();
                            break;
                        case "Entrezgene_source":
                            inEntrezGeneSource = true;
                            break;
                        case "Dbtag_db":
                            if (parser.getElementText().equals("taxon"))
                                inTaxonDbtag = true;
                            break;
                        case "Object-id_id":
                            if (inEntrezGeneSource && inTaxonDbtag)
                                currentXmlExtract.taxId = parser.getElementText();
                            break;
                        case "Gene-commentary_heading":
                            if (parser.getElementText().equals("RefSeq Status")) {
                                inRefSeqStatusCommentary = true;
                            }
                            break;
                        case "Entrezgene_comments":
                            inEntrezgene_comments = true;
                            break;
                        case "Gene-commentary_label":
                            if (inEntrezgene_comments && inRefSeqStatusCommentary) {
                                currentXmlExtract.refSeqStatus = parser.getElementText();
                                inRefSeqStatusCommentary = false;
                            }
                            break;
                        case "Gene-track_status":
                            currentXmlExtract.geneTrackStatusValue = parser.getAttributeValue("", "value");
                            currentXmlExtract.geneTrackStatus = parser.getElementText();
                    }
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    currentTag = parser.getLocalName();
                    switch (currentTag) {
                        case "Entrezgene":
                            geneExtractList.add(currentXmlExtract);
                            break;
                        case "Entrezgene_source":
                            inEntrezGeneSource = false;
                            break;
                        case "Dbtag":
                            inTaxonDbtag = false;
                            break;
                        case "Entrezgene_comments":
                            inEntrezgene_comments = false;
                            break;
                    }
                case XMLStreamConstants.CHARACTERS:
                default:
                    break;
            }
            parser.next();
        }
        openStream.close();
        return geneExtractList;
    }

    private static EntrezgeneProt readEntrezgeneProtFromXml(XMLStreamReader parser) throws XMLStreamException {
        EntrezgeneProt prot = new EntrezgeneProt();

        String currentTag = parser.getLocalName();
        if (!currentTag.equals("Entrezgene_prot"))
            throw new IllegalStateException(
                    "Expected the tag Entrezgene_prot to begin reading protein names but got " + currentTag);
        do {
            parser.next();
            switch (parser.getEventType()) {
                case XMLStreamConstants.START_ELEMENT:
                    currentTag = parser.getLocalName();
                    switch (currentTag) {
                        case "Prot-ref_name_E":
                            prot.addProtrefName(parser.getElementText());
                            break;
                        case "Prot-ref_desc":
                            prot.protrefDesc = parser.getElementText();
                            break;
                    }
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    currentTag = parser.getLocalName();
                    break;
                case XMLStreamConstants.CHARACTERS:
                    break;
            }
        } while (parser.getEventType() != XMLStreamConstants.END_ELEMENT || !currentTag.equals("Entrezgene_prot"));
        return prot;
    }

    public static List<GeneXmlExtract> extractAndWriteGeneInfoToFile(OutputStream osSummaries, OutputStream osProtnames,
                                                                     OutputStream ofRefSeqAndTracStatus, InputStream is) throws XMLStreamException, IOException {
        List<GeneXmlExtract> geneExtractList = extractGeneInfoFromXml(is);
        writeGeneInfoToFile(geneExtractList, osSummaries, osProtnames, ofRefSeqAndTracStatus);
        return geneExtractList;
    }

    public static Set<String> determineMissingTaxIds(File taxIdFile, File storageDirectory, File dbFile,
                                                     File downloadedTaxIdsFile) throws IOException {
        boolean dbFileIsNewer = dbFile.exists() ? downloadedTaxIdsFile.lastModified() < dbFile.lastModified() : true;
        Set<String> missingTaxIds;
        Set<String> taxIds = FileUtils.readLines(taxIdFile, "UTF-8").stream().filter(line -> line.trim().length() != 0)
                .collect(Collectors.toSet());
        if (!dbFileIsNewer) {
            Set<String> downloadedTaxIds = downloadedTaxIdsFile.exists()
                    ? FileUtilities.getReaderFromFile(downloadedTaxIdsFile).lines()
                    .filter(line -> line.trim().length() != 0).collect(Collectors.toSet())
                    : Collections.emptySet();
            log.debug("already created: {}", downloadedTaxIds);
            log.debug("requested: {}", taxIds);
            missingTaxIds = Sets.difference(taxIds, downloadedTaxIds);
            log.debug("difference: {}", missingTaxIds);
            if (missingTaxIds.isEmpty()) {
                log.info("Files for given taxonomy IDs have already been created created in {}", storageDirectory);
                System.exit(0);
            }
            log.info(
                    "Got {} taxonomy IDs for which gene meta information need to be downloaded and {} requested IDs already downloaded",
                    missingTaxIds.size(), taxIds.size() - missingTaxIds.size());
        } else {
            String reason = dbFile.exists()
                    ? "is newer than the existing meta data in " + storageDirectory.getAbsolutePath()
                    : "does not exist";
            log.info("The given gene_info file {}. The data will be downloaded and created from scratch.", reason);
            missingTaxIds = taxIds;
        }
        return missingTaxIds;
    }

    /**
     * Returns the file objects for the meta information files retrieved from
     * NCBI Gene XML data. The list of files contains
     *
     * <ol>
     * <li>gene summaries file</li>
     * <li>gene protein names file</li>
     * <li>taxonomy ID list of organisms for which the other files contain
     * records</li>
     * <li>gene RefSeq status</li>
     * </ol>
     *
     * @param storageDirectory The base directory where to find/store the meta data.
     * @return An ordered list of gene meta data files.
     */
    public static List<File> getMetaFiles(File storageDirectory) {
        File summariesFile = new File(
                storageDirectory.getAbsolutePath() + File.separator + GeneXMLUtils.EG2SUMMARY_FILENAME);
        File proteinNamesFile = new File(
                storageDirectory.getAbsolutePath() + File.separator + GeneXMLUtils.EG2ENTREZGENE_PROT_FILENAME);
        File downloadedTaxIdsFile = new File(
                storageDirectory.getAbsolutePath() + File.separator + GeneXMLUtils.TAXIDS_FILENAME);
        File refseqStatusFile = new File(storageDirectory.getAbsolutePath() + File.separator + GeneXMLUtils.EG2REFSEQ_AND_TRACK_STATUS_FILENAME);
        List<File> geneXmlDownloaderFiles = Arrays.asList(summariesFile, proteinNamesFile, downloadedTaxIdsFile, refseqStatusFile);
        return geneXmlDownloaderFiles;
    }

}
