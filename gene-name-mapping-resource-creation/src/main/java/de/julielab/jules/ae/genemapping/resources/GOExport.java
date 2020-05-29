/** 
 * GOExport.java
 * 
 * Copyright (c) 2008, JULIE Lab. 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 *
 * Author: kampe
 * 
 * Current version: 1.0 	
 * Since version:   1.0
 *
 * Creation date: 29.01.2008 
 * 
 * This class converts Gene Ontology OBO files in an internal format 
 * and splits entries into files by namespace.
 **/

//package de.julielab.stemnet.query;

package de.julielab.jules.ae.genemapping.resources;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GOExport {
	
	private static final int CELLULAR_FILE = 3;
	private static final int MOLECULAR_FILE = 2;
	private static final int BIOLOGICAL_FILE = 1;
	private static final int ID = 1;
	private static final int NAME = 2;
	private static final int NAMESPACE = 3;
	
	protected static final String CELLULAR_COMPONENT = "cellular_component";
	protected static final String MOLECULAR_FUNCTION = "molecular_function";
	protected static final String BIOLOGICAL_PROCESS = "biological_process";
	
	private static final Logger LOGGER = LoggerFactory.getLogger(GOExport.class);	
	private static final String LOGGER_PROPERTIES = "log4j.properties";
	
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		GOExport export = new GOExport();
		if (args.length == 1){
			export.process(args[0]);
		} else {
			System.out.println("usage: GOExport Gene_Ontology");
		}
	}

	protected void process(String go) {
		File goFile = new File(go);
		try {
			BufferedReader br = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(goFile)), Charset.forName("UTF-8")));
			
			String parent = goFile.getParent();
			BufferedWriter process = new BufferedWriter(
					 new FileWriter(BIOLOGICAL_PROCESS));
			BufferedWriter function = new BufferedWriter(
					 new FileWriter(MOLECULAR_FUNCTION));
			BufferedWriter component = new BufferedWriter(
					 new FileWriter(CELLULAR_COMPONENT));
			
			String line;
			while ((line = br.readLine()) != null){
				if (line.equals("")){
					//End of header
					break;
				}
			}
			
			ArrayList<String> term = new ArrayList<String>(10);
			while ((line = br.readLine()) != null){
				if (!line.equals("")){
					term.add(line);
				} else {
					int dest = getNamespace(term);
					String condensedForm = evaluateTerm(term,
													dest == MOLECULAR_FILE);
					switch (dest){
					case BIOLOGICAL_FILE: process.write(condensedForm + "\n");
										  process.flush();
						break;
					case MOLECULAR_FILE: function.write(condensedForm + "\n");
										 function.flush();
						break;
					case CELLULAR_FILE: component.write(condensedForm + "\n");
										component.flush();
						break;
					default: if (condensedForm.length() != 0){
									LOGGER.warn("Unrecognized namespace!");
								}
						break;
					}
					term.clear();
				}
			}
			process.close();
			function.close();
			component.close();
			
			
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
	}

	private int getNamespace(ArrayList<String> term) {
		String namespace = term.get(NAMESPACE);
		int index = namespace.indexOf(':') + 1;
		String namespaceType = namespace.substring(index).trim();
		int dest;
		//Sorted in descending order
		if (namespaceType.equals(BIOLOGICAL_PROCESS)){
			dest = BIOLOGICAL_FILE;
		} else if (namespaceType.equals(MOLECULAR_FUNCTION)){
			dest = MOLECULAR_FILE;
		} else if (namespaceType.equals(CELLULAR_COMPONENT)){
			dest = CELLULAR_FILE;
		} else {
			dest = -1;
		}
		return dest;
	}

	private String evaluateTerm(ArrayList<String> term, boolean isMol) {
		StringBuffer entry = new StringBuffer("");
		//Could also be "[Typedef]"
		if (term.get(0).equals("[Term]")){	
			String id = term.get(ID);
			int index = id.indexOf(':') + 1;
			entry.append(id.substring(index).trim() + "\t");
			
			String name = term.get(NAME);
			index = name.indexOf(':') + 1;
			name = name.substring(index).trim();
			name = (isMol && name.endsWith(" activity"))
					//9 == " activity".length()
					?name.substring(0, name.length()-9)
					:name;	
			entry.append(name);
			
			for (int i = 3; i < term.size(); ++i){
				String feature = term.get(i);
				index = feature.indexOf(':');
				String featureType = feature.substring(0, index);
				if (featureType.contains("synonym")){
					int begin = feature.indexOf('\"') + 1;
					int end = feature.indexOf('\"', begin);
					feature = feature.substring(begin,end);
					feature = (isMol && feature.endsWith(" activity"))
					//9 == " activity".length()
							?feature.substring(0, feature.length()-9)
							:feature;
					entry.append("|" + feature);
				}
			}
		}
		return entry.toString();
	}
	
}

