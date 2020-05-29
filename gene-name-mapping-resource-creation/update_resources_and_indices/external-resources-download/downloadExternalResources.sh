#!/bin/bash
# Downloads and builds resources required to create the JCoRe Gene Mapper resources.
# Call from the directory this script lies in!


if [ -z "$DOWNLOAD_DIR" ]; then
	DOWNLOAD_DIR=download
	echo "Using default download directory $DOWNLOAD_DIR"
else
	echo "Using given download directory $DOWNLOAD_DIR"
fi

RESOURCES="ftp://ftp.ncbi.nih.gov/gene/DATA/gene_info.gz \
ftp://ftp.ncbi.nih.gov/gene/DATA/ASN_BINARY/All_Data.ags.gz \
ftp://ftp.pir.georgetown.edu/databases/idmapping/idmapping.tb.gz \
ftp://ftp.expasy.org/databases/uniprot/current_release/knowledgebase/complete/uniprot_sprot.dat.gz \
ftp://ftp.expasy.org/databases/uniprot/current_release/knowledgebase/complete/uniprot_sprot.xml.gz \
ftp://ftp.pir.georgetown.edu/databases/iprolink/bioThesaurus.dist_7.0.gz \
ftp://ftp.pir.georgetown.edu/databases/iproclass/iproclass.tb.gz \
ftp://ftp.pir.georgetown.edu/databases/iproclass/iproclass.tb.readme \
ftp://ftp.ncbi.nih.gov/gene/GeneRIF/generifs_basic.gz \
ftp://ftp.ncbi.nih.gov/gene/GeneRIF/interactions.gz \
ftp://ftp.ncbi.nih.gov/gene/DATA/gene2go.gz \
ftp://ftp.ebi.ac.uk/pub/databases/GO/goa/UNIPROT/goa_uniprot_all.gpa.gz \
http://www.geneontology.org/ontology/gene_ontology.obo \
"

if [ ! -d "$DOWNLOAD_DIR" ]; then
	echo "Creating download directory $DOWNLOAD_DIR"
	mkdir $DOWNLOAD_DIR;
	if [ ! 0 -eq $? ]; then
		echo "Could not create download directory, aborting.";
		exit 1;
	fi
fi

echo "Downloading the following resources: $RESOURCES"
cd $DOWNLOAD_DIR
echo $RESOURCES | xargs -n 1 -P 8 wget -q

echo "Downloading of external databases finished."
gzip http://www.geneontology.org/ontology/gene_ontology.obo

echo "Now fetching BioConductor databases and building EC Number mapping files"
cd ..
./_downloadBioCDBsAndCreateIDMappings.sh