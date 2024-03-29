/** 
 * BioThesaurusIproClassMerger.java
 * 
 * Copyright (c) 2007, JULIE Lab. 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 *
 * Author: kampe
 * 
 * Current version: 1.3 	
 * Since version:   1.3
 *
 * Creation date: Aug 13, 2007 
 * 
 * Merges IproClass columns UniRef_90, UniRef_50 & Taxon ID into
 * BioThesaurus.
 **/

package de.julielab.jules.ae.genemapping.resources;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;

public class BioThesaurusIproClassMerger {

	HashMap<String, String[]> iproCols;

	BioThesaurusIproClassMerger() {
		this.iproCols = new HashMap<String, String[]>();
	}

	/**
	 * reads in iproclass file
	 * 
	 * @param iproFile
	 *            the iproclass file
	 * @param allColumnsRead
	 *            whether iproclass file contains all columns or just the
	 *            relevant ones (used to save disk space and speed up merging)
	 */
	private void importIproClass(File iproFile, boolean allColumnsRead) {
		try (BufferedReader iproReader = new BufferedReader(new FileReader(
				iproFile))){
			
			String line;
			int counter = 1;
			System.out.println("Importing lines from IproClass!");
			while ((line = iproReader.readLine()) != null) {
				String[] split = line.split("\t");
				// System.out.println(Arrays.toString(split));
				if (allColumnsRead) {
					iproCols.put(split[0].intern(), new String[] { split[1].intern(), split[11].intern(), split[12].intern(),
							split[15].intern() });
				} else {
					iproCols.put(split[0].intern(), new String[] { split[1].intern(), split[2].intern(),
							split[3].intern(), split[4].intern() });
				}

				if (counter % 10000 == 0) {
					System.out.println(counter);
				}
				++counter;
			}
			System.out.println("iproclass: " + iproCols.size()
					+ " lines imported!");

		} catch (FileNotFoundException file) {
			file.printStackTrace();
		} catch (IOException io) {
			io.printStackTrace();
		}
	}

	
	/**
	 * appends cols from iproclass to biothesaurus
	 * @param btFile
	 * @param outputFile
	 */
	private void appendCols(File btFile, String outputFile) {
		try (BufferedReader bioReader = new BufferedReader(
				new FileReader(btFile));
		BufferedWriter bioWriter = new BufferedWriter(new FileWriter(
				outputFile))){
			
			String line;
			System.out.println("Appending columns to BioThesaurus: ");
			int counter = 1;
			while ((line = bioReader.readLine()) != null) {
				String[] split = line.split("\t");
				if (iproCols.containsKey(split[0].intern())) {
					String[] ipro = iproCols.get(split[0].intern());
					line = line + "\t" + ipro[0].intern() + "\t" + ipro[1].intern() + "\t" + ipro[2].intern() + "\t" + ipro[3].intern() + "\n";
				//} else {
				//	line = line + "\tnull\tnull\tnull\n";
				//}
				bioWriter.write(line);
				bioWriter.flush();
				}
				if (counter % 10000 == 0) {
					System.out.println(counter);
				}
				++counter;
			}
			bioReader.close();
			bioWriter.close();
			System.out.println("Finished merging!");
		} catch (FileNotFoundException file) {
			file.printStackTrace();
		} catch (IOException io) {
			io.printStackTrace();
		}

	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		BioThesaurusIproClassMerger merger = new BioThesaurusIproClassMerger();
		if (args.length == 4) {
			File btFile = new File(args[0]);
			File iproFile = new File(args[1]);
			String outputFile = args[2];
			boolean allCols = new Boolean(args[3]);
			if (btFile.isFile() && iproFile.isFile()) {
				merger.importIproClass(iproFile,allCols);
				merger.appendCols(btFile, outputFile);
			} else {
				System.err.println("Could not find one or both files!");
			}

		} else {
			System.err
					.println("Too many/few arguments\nUsage: <bt file> <ip file> <output file> <allCols true/false>");
		}
	}

}
