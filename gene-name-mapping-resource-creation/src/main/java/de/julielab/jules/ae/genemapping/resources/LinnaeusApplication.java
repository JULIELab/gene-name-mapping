package de.julielab.jules.ae.genemapping.resources;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.uima.UIMAException;
import org.apache.uima.analysis_engine.AnalysisEngine;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.fit.factory.AnalysisEngineFactory;
import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.util.JCasUtil;
import org.apache.uima.jcas.JCas;

import de.julielab.jcore.types.Organism;

public class LinnaeusApplication {

	public enum Index {
		SPECIFIC, RANGE
	}

	public static void main(String[] args) throws IOException, UIMAException {
		if (args.length != 3) {
			System.err.println("Usage: " + LinnaeusApplication.class.getSimpleName()
					+ " <tab separated input file> <comma separated column indexes to replace with tax IDs or column range given by from-to where to may be omitted> <output file>");
			System.exit(1);
		}
		File input = new File(args[0]);
		File output = new File(args[2]);
		List<Integer> specifiedCols = null;
		int specifiedRangeStart = -1;
		int specifiedRangeEnd = -1;
		Index index;
		if (args[1].contains(",") || !args[1].contains("-")) {
			specifiedCols = Stream.of(args[1].split(",")).map(i -> Integer.parseInt(i)).collect(Collectors.toList());
			index = Index.SPECIFIC;
		} else {
			index = Index.RANGE;
			String[] split = args[1].split("-");
			specifiedRangeStart = Integer.parseInt(split[0]);
			if (split.length == 2) {
				specifiedRangeEnd = Integer.parseInt(split[1]);
			} else if (split.length > 2)
				throw new IllegalArgumentException("The range specification '" + args[1]
						+ "' is invalid, please specify one begin and at most one end offset.");
		}
		// we need this variable copying for the lambdas that require
		// effectively final variables
		List<Integer> cols = specifiedCols;
		int rangeStart = specifiedRangeStart;
		int rangeEnd = specifiedRangeEnd;

		String descriptor = "de.julielab.jules.resources.linnaeus.genera_proxies.jules-linnaeus-species-ae";

		LinnaeusApplication app = new LinnaeusApplication(descriptor);

		try (BufferedWriter bw = Files.newBufferedWriter(output.toPath())) {
			Files.lines(input.toPath(), Charset.forName("UTF-8")).map(l -> l.split("\\t")).map(split -> {
				// First, enumerate the column indexes we want to process of
				// this
				// specific line.
				// If the indexes were given explicitly, there is not much to
				// do. If
				// it was a range, we now enumerate the columns ourselves.
				List<Integer> effectiveCols = cols != null ? new ArrayList<>(cols) : new ArrayList<>();
				if (index == Index.RANGE) {
					int end = rangeEnd == -1 ? split.length - 1 : rangeEnd;
					for (int col = rangeStart; col <= end; ++col)
						effectiveCols.add(col);
				}
				for (int col : effectiveCols)
					split[col] = app.tagSpecies(split[col]);
				return split;
			}).filter(split -> {
				List<Integer> effectiveCols = cols != null ? new ArrayList<>(cols) : new ArrayList<>();
				if (index == Index.RANGE) {
					int end = rangeEnd == -1 ? split.length - 1 : rangeEnd;
					for (int col = rangeStart; col <= end; ++col)
						effectiveCols.add(col);
				}
				for (int col : effectiveCols)
					if (split[col] != null && split[col].trim().length() > 0)
						return true;
				return false;
			}).forEach(split -> {
				try {
					bw.write(Stream.of(split).collect(Collectors.joining("\t"))+"\n");
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
		}
	}

	private final AnalysisEngine linnaeusAE;
	private JCas jcas;

	public LinnaeusApplication(String descriptor) throws IOException, UIMAException {
		File descriptorFile = new File(descriptor);
		if (descriptorFile.exists()) {
			linnaeusAE = AnalysisEngineFactory.createEngineFromPath(descriptorFile.getAbsolutePath());
		} else {
			// in this case, the descriptor should be on the classpath
			linnaeusAE = AnalysisEngineFactory.createEngine(descriptor);
		}
		jcas = JCasFactory.createJCas("de.julielab.jcore.types.jcore-all-types");
	}

	public String tagSpecies(String input) {
		try {
			File inputFile = new File(input);
			String text;
			if (inputFile.exists()) {
				text = new String(Files.readAllBytes(inputFile.toPath()), Charset.forName("UTF-8"));
			} else {
				text = input;
			}
			if (text == null || text.length() == 0) {
				System.err.println("Text contents are empty, aborting process.");
				return "";
			}
			jcas.setDocumentText(text);
			linnaeusAE.process(jcas);
			Collection<Organism> organisms = JCasUtil.select(jcas, Organism.class);
			String taxIds = organisms.stream().map(o -> o.getResourceEntryList(0).getEntryId())
					.collect(Collectors.joining(";"));
			return taxIds;
		} catch (IOException e) {
			e.printStackTrace();
		} catch (AnalysisEngineProcessException e) {
			e.printStackTrace();
		} finally {
			jcas.reset();
		}
		return "";
	}

}
