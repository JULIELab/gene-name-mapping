package de.julielab.jules.ae.genemapping.resources;

import java.io.File;
import java.io.IOException;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.search.spell.LuceneDictionary;
import org.apache.lucene.search.spell.SpellChecker;
import org.apache.lucene.store.FSDirectory;

import de.julielab.jules.ae.genemapping.index.SynonymIndexFieldNames;

public class SpellCheckerIndexGenerator {

	public static void main(String[] args) throws Exception {
		
		if (args.length != 2) {
			System.err.println(
					"Usage: SpellCheckerIndexGenerator <resourcesDirectory> <geneSynonymIndicesDirectory>");
			System.exit(1);
		}
		
		String resPath = args[0];
		File resDir = new File(resPath);
		if (!resDir.isDirectory()) {
			System.err.println("Could not find resources directory");
			System.exit(1);
		}
		if (!resPath.endsWith(File.separator)) {
			resPath = resPath + File.separator;
		}

		String indexPath = args[1];
		if (!indexPath.endsWith("/")) {
			indexPath = indexPath + "/";
		}
		
		File geneIndexDir = new File(indexPath + "geneSynonymIndex");
		File proteinIndexDir = new File(indexPath + "proteinSynonymIndex");
		File geneSpellingIndexDir = new File(indexPath + "geneSpellingIndex");
		File proteinSpellingIndexDir = new File(indexPath + "proteinSpellingIndex");
		
		System.out.println("Writing gene spelling index to " + geneSpellingIndexDir.getAbsolutePath());
		createSpellingIndex(geneIndexDir, geneSpellingIndexDir);
//		System.out.println("Writing protein spelling index to " + proteinSpellingIndexDir.getAbsolutePath());
//		createSpellingIndex(proteinIndexDir, proteinSpellingIndexDir);
		System.out.println("Done.");
	}

	public static void createSpellingIndex(File mentionIndexDir, File spellingIndexDir) throws IOException {
		IndexReader reader = DirectoryReader.open(FSDirectory.open(mentionIndexDir.toPath()));
		LuceneDictionary dictionary = new LuceneDictionary(reader,
				SynonymIndexFieldNames.LOOKUP_SYN_FIELD);
		WhitespaceAnalyzer wsAnalyzer = new WhitespaceAnalyzer();
		IndexWriterConfig iwc = new IndexWriterConfig(wsAnalyzer);
		iwc.setOpenMode(OpenMode.CREATE);
		try (SpellChecker sc = new SpellChecker(FSDirectory.open(spellingIndexDir.toPath()))) {
			sc.indexDictionary(dictionary, iwc, true);
		}
	}

}
