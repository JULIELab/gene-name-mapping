# Gene Name Mapping

The code in this repository is used to find NCBI Gene Ids for textual mentions of gene names that have been found in scientific literature text by entity recognition software. Found gene names are searched in NCBI Gene names and synonyms via a [Lucene](https://lucene.apache.org/) index and disambiguated using [GeneRIF](https://www.ncbi.nlm.nih.gov/gene/about-generif) sentences.

The code found here shares its foundations with GeNo [1] but has been
1.  adapted to the specific needs of project partners interested in clinical relevant gene mentions and
2.  updated regarding the underlying databases, most importantly NCBI Gene itself.

To let the mapping code run, a [UIMA](https://uima.apache.org/) pipeline is required in which this component can be embedded. The simplest way would be to use [JCoRe](https://github.com/JULIELab/jcore-base) components to form the pipeline. The descriptor for the gene mapping component is found at `gene-name-mapping-ae/src/main/resources/de/jules/ae/genemapping/desc/genemapping-ae.xml`.

To build the resources from scratch, run the `gene-name-mapping-resource-creation/update_resources_and_indices/metaScript.sh` script. Note, however, that a number of downloaded source resources are required. To get those, run `gene-name-mapping-resource-creation/update_resources_and_indices/downloadExternalResources.sh`. Optional: To set the path to specific resource files, make the appropriate changes to `gene-name-mapping-resource-creation/update_resources_and_indices/setCustomResourcePaths.sh` which is called from within `metaScripts.sh`.




[1] Wermter, J., Tomanek, K., & Hahn, U. (2009). High-performance gene name normalization with GeNo. Bioinformatics (Oxford, England), 25(6), 815–821. https://doi.org/10.1093/bioinformatics/btp071
