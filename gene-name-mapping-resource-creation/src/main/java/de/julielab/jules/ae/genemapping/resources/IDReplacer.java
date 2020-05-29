package de.julielab.jules.ae.genemapping.resources;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class takes as input a database or table-shaped file, a database source
 * ID column index, an ID mapping file and an source ID mapping file column
 * index. The indexes are zero-based. The program will replace the source IDs in
 * the database column with the target IDs that they are mapped to in the
 * mapping file. The mapping file is allowed to contain n-to-m-mappings. In such
 * cases, each database record line will be output for each ID mapping target.
 * 
 * All files must use the tab character as the column separator.
 * 
 * @author faessler
 *
 */
public class IDReplacer {

	private File dbFile;
	private int dbIndex;
	private File mappingFile;
	private int mappingIndex;
	private Map<String, List<String>> mapping;
	private File output;

	public Map<String, List<String>> getMapping() {
		return mapping;
	}

	public IDReplacer(File dbFile, int dbIndex, File mappingFile, int mappingIndex) throws IOException {
		this(dbFile, dbIndex, mappingFile, mappingIndex, null);
	}

	public IDReplacer(File dbFile, int dbIndex, File mappingFile, int mappingIndex, File output) throws IOException {
		this.dbFile = dbFile;
		this.dbIndex = dbIndex;
		this.mappingFile = mappingFile;
		this.mappingIndex = mappingIndex;
		this.output = output;

		if (dbFile != null && (!dbFile.exists() || !dbFile.isFile()))
			throw new IOException(
					dbFile.getAbsolutePath() + " does not exist or is not a regular file (maybe a directory?)");
		if (mappingFile != null && (!mappingFile.exists() || !mappingFile.isFile()))
			throw new IOException(
					mappingFile.getAbsolutePath() + " does not exist or is not a regular file (maybe a directory?)");
	}

	public void readMapping() throws IOException {
		// read the lines of the mapping file, split them at tab, group them by
		// the values of the
		// specified column but do not group the whole split but map the split
		// to the other column (which is always at 1 - mappingIndex because the
		// specified index is 0 or 1)
		mapping = Files.lines(mappingFile.toPath(), Charset.forName("UTF-8")).map(l -> l.split("\\t"))
				.collect(Collectors.groupingBy(s -> s[mappingIndex],
						Collectors.mapping(s -> s[1 - mappingIndex], Collectors.toList())));

	}

	public void replace() throws IOException {
		// read the lines of the database file, split them at tab, filter those lines away where we don't have a mapping for,
		// replace the specified source ID column successively by each of the
		// mapped target ID and create a new record line by joining the fields
		// by a tab character (flatMap allows us here to create multiple output
		// lines for a single input line which we need since the source ID might
		// be mapped to multiple target IDs).
		Stream<String> mappedDbLines = Files.lines(dbFile.toPath(), Charset.forName("UTF-8")).map(l -> l.split("\\t")).filter(s -> mapping.containsKey(s[dbIndex]))
				.flatMap(s -> mapping.get(s[dbIndex]).stream().map(id -> {
					s[dbIndex] = id;
					return Stream.of(s).collect(Collectors.joining("\t"));
				}));
		if (output != null) {
			Files.write(output.toPath(), (Iterable<String>) () -> mappedDbLines.iterator(), Charset.forName("UTF-8"));
		} else {
			mappedDbLines.forEach(System.out::println);
		}
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 4 || args.length > 5) {
			System.out.println("Usage: " + IDReplacer.class.getName()
					+ " <database file> <database ID column index> <mapping file> <mapping file source ID column index> [output file]");
			System.exit(1);
		}

		File dbFile = new File(args[0]);
		int dbIndex = Integer.parseInt(args[1]);
		File mappingFile = new File(args[2]);
		int mappingIndex = Integer.parseInt(args[3]);
		File output = null;
		if (args.length > 4 && args[4].trim().length() > 0)
			output = new File(args[4]);

		IDReplacer replacer = new IDReplacer(dbFile, dbIndex, mappingFile, mappingIndex, output);
		replacer.readMapping();
		replacer.replace();

	}

}
