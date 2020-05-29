package de.julielab.jules.ae.genemapping.resources;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;

import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * 
 * @author engelmann, faessler
 * 
 *         The UniprotDictCreator is used to retrieve relevant content for the
 *         uniprot-dictionary as found in the shell script makeGeneDictionary.sh
 *         for the creation of the resources. Note that this solution was
 *         reverse-engineered looking at the already existing uniprot.all.dict
 *         and searching for the elements in question to retrieve from
 *         uniprot.sprot.xml.
 *         A later change was made by faessler to use the primary accession ID instead
 *         of the UniProt mnemonic ID (IDs like IL2_MOUSE).
 * 
 */
public class UniprotDictCreator {

	private static HashMap<String, ArrayList<String>> dictContent = new HashMap<String, ArrayList<String>>();

	public static void main(String[] args) {
		if (args.length == 2) {
			File inputFile = new File(args[0]);
			File outputFile = new File(args[1]);
			UniprotDictCreator dictCreator = new UniprotDictCreator();
			dictCreator.readEntries(inputFile);
			dictCreator.writeEntries(outputFile);
		} else {
			System.err
					.println("usage:\nUniProtDictCreator <inputFile> <outputFile>");
			System.exit(-1);
		}
	}

	public void readEntries(File inputFile) {
		boolean isInEntry;
		boolean retrievedAccession = false;
		boolean isInRecommendedName = false;
		boolean isInGene = false;
		String accession = "";
		ArrayList<String> otherNames = new ArrayList<String>();

		try {
			InputStream fis = new FileInputStream(inputFile);
			if (inputFile.getName().endsWith(".gz"))
				fis = new GZIPInputStream(fis);
			XMLStreamReader reader = XMLInputFactory.newInstance()
					.createXMLStreamReader(fis);
			while (reader.hasNext()) {
				reader.next();
				if (reader.getEventType() == XMLStreamReader.START_ELEMENT
						&& reader.getLocalName().equals("entry")) {
					isInEntry = true;
					retrievedAccession = false;
					while (isInEntry && reader.hasNext()) {
							reader.next();
							if (reader.getEventType() == XMLStreamReader.START_ELEMENT) {
								String localName = reader.getLocalName();
								if (localName.equals("accession")
										&& retrievedAccession == false) {
									accession = reader.getElementText();
									// get the primary accession (or the only one)
									accession = accession.split(",", 2)[0];
									retrievedAccession = true;
								} else if (localName.equals("recommendedName")) {
									isInRecommendedName = true;
									while (isInRecommendedName && reader.hasNext()) {
										reader.next();
										if (reader.getEventType() == XMLStreamReader.END_ELEMENT
												&& reader
														.getLocalName()
														.equals("recommendedName")) {
											isInRecommendedName = false;
										} else if (reader.getEventType() == XMLStreamReader.START_ELEMENT
												&& reader.getLocalName()
														.contains("Name")) {
											String otherName = reader
													.getElementText();
											// The -1 is the synonym priority: the recommended name gets -1 (like the official symbol from NCBI gene)
											otherNames.add(otherName+"\t-1");
										}
									}
								} else if (localName.equals("gene")) {
									isInGene = true;
									while (isInGene && reader.hasNext()) {
										reader.next();
										if (reader.getEventType() == XMLStreamReader.END_ELEMENT
												&& reader.getLocalName()
														.equals("gene")) {
											isInGene = false;
										}
										if (reader.getEventType() == XMLStreamReader.START_ELEMENT
												&& reader.getLocalName()
														.equals("name")) {
											if (reader.getAttributeValue(0)
													.equals("primary")) {
												String otherName = reader
														.getElementText();
												// the gene name gets priority 0
												otherNames.add(otherName+"\t0");
												isInGene = false;
												isInEntry = false;
												dictContent.put(
														new String(accession),
														new ArrayList<String>(
																otherNames));
												accession = "";
												otherNames.clear();
											}
										}

									}
								}
							}

					}
				}
			}
			reader.close();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (XMLStreamException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (FactoryConfigurationError e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public void writeEntries(File outputFile) {
		try {
			FileWriter writer = new FileWriter(outputFile);
			for (String name : dictContent.keySet()) {
				for (String otherName : dictContent.get(name)) {
					writer.write(otherName + "\t" + name + "\n");
				}
			}
			writer.flush();
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

}
