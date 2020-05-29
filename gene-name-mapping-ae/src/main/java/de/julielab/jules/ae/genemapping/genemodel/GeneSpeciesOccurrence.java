package de.julielab.jules.ae.genemapping.genemodel;

/**  Descending order of reliability according to Hakenberg et al. (2008)
 *  (1) same compound noun: ‘murine Eif4g1’,
    (2) same phrase, including enumerations: ‘rat and murine Eif4g1’ or
    (3) same sentence.
    (4) previous sentence mentions a species,
    (5) title of abstracts mentions a species,
    (6) first sentence of abstract mentions a species,
    (7) a species occurs anywhere in the abstract or
    (8) a species was annotated as MeSH term.
    
    If no species are mentioned anywhere, a default one (9) might be set.
 */
public enum GeneSpeciesOccurrence	 {
	COMPOUND_PRECEED, COMPOUND_SUCCEED, /** For GNAT species assigner */ COMPOUND, PHRASE, SENTENCE, PREVIOUS_SENTENCE, TITLE, FIRST, ANYWHERE, MESH, DEFAULT, SPECIES_PREFIX
}
