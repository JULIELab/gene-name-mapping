package de.julielab.jules.ae.genemapping.scoring;

public class LuceneScorer extends Scorer {

	@Override
	public double getScore(String term1, String term2) throws RuntimeException {
		throw new RuntimeException(
				"This is just a place holder class, there is no scoring implementation. The Lucene score should be used directly from the search in LuceneCandidateRetrieval.");
	}

	@Override
	public String info() {
		return "Lucene Scorer";
	}

	@Override
	public int getScorerType() {
		return 10;
	}

}
