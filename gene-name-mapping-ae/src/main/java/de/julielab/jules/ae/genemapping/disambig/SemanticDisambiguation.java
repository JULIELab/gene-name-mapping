package de.julielab.jules.ae.genemapping.disambig;

import de.julielab.jules.ae.genemapping.DocumentMappingResult;
import de.julielab.jules.ae.genemapping.MentionMappingResult;
import de.julielab.jules.ae.genemapping.utils.GeneMappingException;

/**
 * Implementing classes of this interface are used at the very end of the gene
 * mapping process. After gene database candidates are drawn for a gene name
 * found in text, it must not just be decided which gene database identifier
 * applies but even if the respective gene text mention has a database entry at
 * all. Since the gene mapper has access to gene background information, this is
 * even a method of removing false positives produced by gene finders which did
 * not have these information.
 * 
 * @author faessler
 *
 */
public interface SemanticDisambiguation {
	MentionMappingResult disambiguateMention(MentionDisambiguationData disambiguationData) throws GeneMappingException;
	DocumentMappingResult disambiguateDocument(DocumentDisambiguationData disambiguationData) throws GeneMappingException;

	SemanticIndex getSemanticIndex();
}
