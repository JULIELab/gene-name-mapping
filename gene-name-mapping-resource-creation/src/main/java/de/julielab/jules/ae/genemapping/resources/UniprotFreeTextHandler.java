/** 
 * UniprotFreeTextHandler.java
 * 
 * Copyright (c) 2007, JULIE Lab. 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 *
 * Author: kampe
 * 
 * Current version: 1.0 	
 * Since version:   1.0
 *
 * Creation date: 03.02.2008 
 * 
 * General handler class for UniprotFreeTextExtractor.
 * All methods are derived from DefaultHandler.
 **/

//package de.julielab.stemnet.query;
package de.julielab.jules.ae.genemapping.resources;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.TreeMap;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

public class UniprotFreeTextHandler extends DefaultHandler {

	protected HashMap<String, String[]> uniprot2EntrezMap;

	protected TreeMap<String, BufferedWriter> freetextFiles;

	public UniprotFreeTextHandler(String output) {
		this.elements = new LinkedList<String>();
		this.primaryAccession = "";
		this.category = "";
		this.comments = new TreeMap<String, StringBuffer>();
		this.freetextFiles = new TreeMap<String, BufferedWriter>();
		this.parent = new File(output).getParent() + File.separator;
	}

	// Just for the compiler.
	UniprotFreeTextHandler() {
	}

	// XML-Tags
	private LinkedList<String> elements;

	// temporary store to speed up parsing
	private TreeMap<String, StringBuffer> comments;

	private String primaryAccession;

	private String category;

	private String parent;

	private boolean isOnlineInformation;

	public void startDocument() {
		primaryAccession = "";
		this.comments.clear();
	}

	/**
	 * This method sets all necessary switches (tag attributes) and updates tag
	 * hierarchy information.
	 */
	public void startElement(java.lang.String uri, java.lang.String localName, java.lang.String qName,
			Attributes atts) {
		// extracts only those text elements that are not "online information".
		if (qName.equals("comment")) {
			int attsIndex = atts.getIndex("type");
			String value = atts.getValue(attsIndex);
			isOnlineInformation = value.equalsIgnoreCase("online information");
			category = value;
		}
		elements.add(qName);
	}

	public void endDocument() {
		// If the parser would stop earlier, this if-statement
		// would not be necessary
		if (uniprot2EntrezMap.containsKey(primaryAccession)) {
			for (String comment : comments.keySet()) {
				String uniName = "uniprot_id2" + comment;
				String entrezName = "entrezgene_id2" + comment;
				try {
					BufferedWriter uniRaf = (freetextFiles.containsKey(uniName)) ? freetextFiles.get(uniName)
							: new BufferedWriter(new FileWriter(uniName));
					BufferedWriter entrezRaf = (freetextFiles.containsKey(entrezName)) ? freetextFiles.get(entrezName)
							: new BufferedWriter(new FileWriter(entrezName));
					String text = "\t" + comments.get(comment) + "\n";
					uniRaf.write(primaryAccession + text);
					String[] entrezIDs = uniprot2EntrezMap.get(primaryAccession);
					for (String id : entrezIDs) {
						entrezRaf.write(id + text);
					}
					freetextFiles.put(uniName, uniRaf);
					freetextFiles.put(entrezName, entrezRaf);
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * endElement moves data acquired by characters() into storage structure,
	 * updates tag hierarchy information (level -1) & resets all switches in
	 * case an entry ended
	 */
	public void endElement(java.lang.String uri, java.lang.String localName, java.lang.String qName) {
		elements.removeLast();
	}

	private void stopParser() throws SAXException {
		// TODO: This is clear no fatal error, but merely a way to stop
		// parsing. Find a better way to control the parser.
		fatalError(new SAXParseException("No corresponding ID!", null));
	}

	/**
	 * This method holds the text between two tags for modification purposes.
	 * (and acquires entry name).
	 * 
	 * @throws SAXException
	 */
	public void characters(char[] ch, int start, int length) throws SAXException {
		String element = elements.getLast();
		// we only want the primary accession which is the first one
		if (primaryAccession.length() == 0 && element.equals("accession")) {
			int size = elements.size();
			// the size of elements is the depth of the current element because
			// in #endElement we always remove the last element
			// Thus, the test is: are we at depth 2 or deeper? What we actually
			// want to know is if we are within an entry element, which should
			// trivially be the case so I'm not sure the test is necessary
			if (size >= 2) {
				element = elements.get(size - 2);
				if (element.equals("entry")) {
					primaryAccession = (new String(ch)).substring(start, start + length);
					// if (!uniprot2EntrezMap.containsKey(entryName)){
					// stopParser();
					// }
				}
			}
		} else if (element.equals("text")) {
			int size = elements.size();
			if (size >= 2) {
				element = elements.get(size - 2);
				if (element.equalsIgnoreCase("comment") && !isOnlineInformation) {
					StringBuffer storage = (comments.containsKey(category)) ? comments.get(category)
							: new StringBuffer("");

					storage.append((storage.length() == 0) ? (new String(ch)).substring(start, start + length)
							: " " + (new String(ch)).substring(start, start + length));
					comments.put(category, storage);
				}
			}
		} else if (element.equals("keyword")) {
			StringBuffer keywords = (comments.containsKey("keyword")) ? comments.get("keyword") : new StringBuffer("");
			keywords.append((keywords.length() == 0) ? (new String(ch)).substring(start, start + length)
					: " " + (new String(ch)).substring(start, start + length));
			comments.put("keyword", keywords);
		}
	}
}
