package de.julielab.jules.ae.genemapping.resources.ncbigene;

public class GeneXmlExtract {

	public String geneId;
	public String summary;
	public EntrezgeneProt entrezgeneProt;
	public String taxId;
	/**
	 * The status of the gene sequence analysis, see <a href="https://www.ncbi.nlm.nih.gov/books/NBK3841/#EntrezGene.Summary_2">https://www.ncbi.nlm.nih.gov/books/NBK3841/#EntrezGene.Summary_2</a>
	 * and <a href="https://www.ncbi.nlm.nih.gov/books/NBK21091/table/ch18.T.refseq_status_codes/?report=objectonly">https://www.ncbi.nlm.nih.gov/books/NBK21091/table/ch18.T.refseq_status_codes/?report=objectonly</a>.
	 */
	public String refSeqStatus;
	/**
	 * The content of the 'value' attribute of the Gene-track_status element
	 */
	public String geneTrackStatusValue;
	/**
	 * The text content of the Gene-track_status element
	 */
	public String geneTrackStatus;


    @Override
    public String toString() {
        return "GeneXmlExtract{" +
                "geneId='" + geneId + '\'' +
                ", summary='" + summary + '\'' +
                ", entrezgeneProt=" + entrezgeneProt +
                ", taxId='" + taxId + '\'' +
                ", refSeqStatus=" + refSeqStatus +
                '}';
    }
}