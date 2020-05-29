package de.julielab.jules.ae.genemapping.mappingcores;

import de.julielab.jules.ae.genemapping.CandidateRetrieval;
import de.julielab.jules.ae.genemapping.DocumentMappingResult;
import de.julielab.jules.ae.genemapping.MentionMappingResult;
import de.julielab.jules.ae.genemapping.disambig.SemanticDisambiguation;
import de.julielab.jules.ae.genemapping.genemodel.GeneDocument;
import de.julielab.jules.ae.genemapping.genemodel.GeneMention;
import de.julielab.jules.ae.genemapping.utils.GeneMappingException;
import de.julielab.jules.ae.genemapping.utils.norm.TermNormalizer;

public interface MappingCore {
	MentionMappingResult map(GeneMention geneMention) throws GeneMappingException;

	SemanticDisambiguation getSemanticDisambiguation();
	CandidateRetrieval getCandidateRetrieval();
	TermNormalizer getTermNormalizer();

	DocumentMappingResult map(GeneDocument document) throws GeneMappingException;
}
