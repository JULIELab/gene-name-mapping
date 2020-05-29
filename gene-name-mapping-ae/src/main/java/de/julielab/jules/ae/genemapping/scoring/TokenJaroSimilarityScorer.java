/** 
 * JaroWinklerScorer.java
 * 
 * Copyright (c) 2007, JULIE Lab. 
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0 
 *
 * Author: tomanek
 * 
 * Current version: 1.4.2 	
 * Since version:   1.4
 *
 * Creation date: Aug 16, 2007 
 * 
 * a scorer based on jaro winkler similarity
 **/

package de.julielab.jules.ae.genemapping.scoring;

import de.julielab.jules.ae.genemapping.GeneMapping;

public class TokenJaroSimilarityScorer extends Scorer {

	private TokenJaroSimilarity jaro = null;
	
	public TokenJaroSimilarityScorer() {
		jaro = new TokenJaroSimilarity();
	}
	public double getScore(String term1, String term2) throws RuntimeException {
		
		if (isPerfectMatch(term1,term2)) {
			return PERFECT_SCORE;
		}
		
		return jaro.tokenScore(term1, term2);
		
	}

	public String info() {
		return "TokenJaroWinklerScorer";
	}
	@Override
	public int getScorerType() {
		return GeneMapping.TOKEN_JAROWINKLER_SCORER;
	}
	
}
