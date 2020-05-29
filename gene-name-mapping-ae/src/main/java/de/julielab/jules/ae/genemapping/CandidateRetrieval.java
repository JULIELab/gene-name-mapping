package de.julielab.jules.ae.genemapping;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import de.julielab.jules.ae.genemapping.genemodel.GeneMention;
import de.julielab.jules.ae.genemapping.utils.GeneCandidateRetrievalException;

public interface CandidateRetrieval {
	List<SynHit> getCandidates(String originalSearchTerm) throws GeneCandidateRetrievalException;

	List<SynHit> getCandidates(GeneMention geneMention) throws GeneCandidateRetrievalException;

	List<SynHit> getCandidates(GeneMention geneMention, String organism) throws GeneCandidateRetrievalException;

	List<SynHit> getCandidates(GeneMention geneMention, Collection<String> organisms)
			throws GeneCandidateRetrievalException;

	List<SynHit> getCandidates(String geneMentionText, String organism) throws GeneCandidateRetrievalException;

	List<SynHit> getCandidates(String geneMentionText, Collection<String> organism)
			throws GeneCandidateRetrievalException;

	String mapGeneIdToTaxId(String geneId) throws IOException;

	/**
	 * Retrieves the first index hit for each ID.
	 * 
	 * @param ids
	 *            The gene IDs for which to retrieve a single index hit.
	 * @return One index entry - or null - for each input ID.
	 * @throws IOException
	 *             If there is an issue reading the index.
	 */
	List<SynHit> getIndexEntries(List<String> ids) throws IOException;
	
	List<String> getSynonyms(String id) throws IOException;

}
