package de.julielab.jules.ae.genemapping.resources;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import com.ximpleware.extended.*;
import de.julielab.xml.JulieXMLBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;

import com.bluecast.xml.Piccolo;

public class UniprotFreeTextExtractor {

    // Approximates amount of hash space, so uniprot2EntrezMap
    // in UniprotFreeTextHandler won't rehash. (0.75 is default fill factor)
    private static final double FILL_FACTOR = 18 * 0.75;
    private static final String FEATURE_NAMESPACE_PREFIXES = "http://xml.org/sax/features/namespace-prefixes";
    private static final String FEATURE_NAMESPACE = "http://xml.org/sax/features/namespaces";

    private static final Logger LOGGER = LoggerFactory.getLogger(UniprotFreeTextExtractor.class);


    /**
     * @param args
     */
    public static void main(String[] args) throws ParseExceptionHuge, NavExceptionHuge, XPathParseExceptionHuge, XPathEvalExceptionHuge {
        if (args.length == 2) {
            UniprotFreeTextExtractor extractor = new UniprotFreeTextExtractor();
            File uniProt = new File(args[0]);
            if (uniProt.isFile() && args[0].length() > 0) {
                Piccolo xParser = new Piccolo();
                UniprotFreeTextHandler handler = new UniprotFreeTextHandler(args[1]);
                try {
                    /*
                     * "As per the SAX2 specification, namespace handling is on
                     * by default. You can improve performance by turning it
                     * off." See org.xml.sax.XMLReader.setFeature for details.
                     */
                    xParser.setFeature(FEATURE_NAMESPACE, false);
                    xParser.setFeature(FEATURE_NAMESPACE_PREFIXES, true);

                } catch (SAXNotSupportedException e) {
                    e.printStackTrace();
                } catch (SAXNotRecognizedException e) {
                    e.printStackTrace();
                }
                xParser.setContentHandler(handler);
                try {
                    extractor.fillMap(args[1], handler);
                    extractor.splitEntries(xParser, uniProt, handler);
                } catch (IOException io) {
                    io.printStackTrace();
                } catch (SAXException sax) {
                    sax.printStackTrace();
                }
            } else {
                System.out.println(args[0]);
                System.err.println("Could not find UniProt file!");
            }
        }

    }

    private void splitEntries(Piccolo xParser, File uniProt, UniprotFreeTextHandler handler)
            throws IOException, SAXException, XPathParseExceptionHuge, NavExceptionHuge, ParseExceptionHuge, XPathEvalExceptionHuge {


        JulieXMLBuffer buffer = new JulieXMLBuffer();
        buffer.readFile(uniProt.getAbsolutePath());
        VTDGenHuge vg = new VTDGenHuge();
        vg.setDoc(buffer);
        vg.parse(true);
        final VTDNavHuge nav = vg.getNav();
        final AutoPilotHuge ap = new AutoPilotHuge(nav);
        ap.selectXPath("/uniprot/entry");
        int counter = 0;
        while (ap.evalXPath() != -1) {
            final long[] elementFragment = nav.getElementFragment();
            long offset = elementFragment[0];
            long length = elementFragment[1];
            final String entryXml = new String(buffer.getFragment(offset, length), StandardCharsets.UTF_8);

            try {
                xParser.parse(new InputSource(new StringReader(entryXml)));
            } catch (SAXParseException sax) {
                if (sax.getMessage().equals("No corresponding ID!")) {
                }
            }
            ++counter;
            if (counter % 50000 == 0) {
                LOGGER.info(counter + " entries processed!");
            }
        }
        closeFileHandles(handler);
    }

    private void closeFileHandles(UniprotFreeTextHandler handler) {
        for (String filename : handler.freetextFiles.keySet()) {
            BufferedWriter raf = handler.freetextFiles.get(filename);
            try {
                raf.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    /**
     * Expected format:
     * <code>UniProt Primary Accession ID<tab>Entrez Gene ID</code>. The UniProt
     * accession might occurr multiple times in case it has been mapped to
     * multiple Entrez Gene Ids.
     *
     * @param string
     * @param handler
     */
    private void fillMap(String string, UniprotFreeTextHandler handler) {
        File idFile = new File(string);
        long fileSize = idFile.length();
        try {
            BufferedReader br = new BufferedReader(new FileReader(idFile));
            HashMap<String, String[]> uniprot2EntrezMap = new HashMap<String, String[]>((int) (fileSize / FILL_FACTOR));
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.equals("")) {
                    String[] entry = line.split("\t");
                    String[] egIds = uniprot2EntrezMap.get(entry[0]);
                    if (egIds == null) {
                        uniprot2EntrezMap.put(entry[0], new String[]{entry[1]});
                    } else {
                        String[] newArray = new String[egIds.length + 1];
                        System.arraycopy(egIds, 0, newArray, 0, egIds.length);
                        newArray[newArray.length - 1] = entry[1];
                        uniprot2EntrezMap.put(entry[0], newArray);
                    }
                }
            }
            System.out.println("ID map size: " + uniprot2EntrezMap.size());
            handler.uniprot2EntrezMap = uniprot2EntrezMap;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

}
