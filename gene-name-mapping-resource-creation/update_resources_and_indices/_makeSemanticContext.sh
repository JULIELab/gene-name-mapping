# Parameters:
# $1: external resources directory
# $2: final context files output directory 
# $3: organism list with NCBI Taxonomy IDs to restrict dictionary creation to
# $4: file where to store the organism filtered gene_info file is located as created by the makeGeneDictionary.sh script
# $5: directory where downloaded NCBI Gene XML files can be stored to or read from, if already existing
# This script requires a list of files that originally are downloaded from the
# internet. This is not done in this script. Instead, the default locations
# specified in the downloadExternalResources.sh script are given. The files in question are:
# * generifs_basic.gz        - context information about genes
# * gene2go.gz               - Gene Ontology associations to NCBI Gene IDs
# * goa_uniprot_all.gpa.gz   - Gene Ontology associations to UniProt proteins
# * gene_ontology.obo.gz     - the Gene Ontology to get context information from
# * uniprot_sprot.xml.gz     - UniProt / Sprot protein database
# * interactions.gz          - 
# * gene_info.gz             - only as a fallback if the organism filtered gene_info file is not given
# The sole purpose of all these files is to give textual material describing genes and proteins that
# we can use do disambiguate by context when multiple genes have the same name as a gene mention found
# in text.

DEFAULT_DOWNLOAD_DIR=$1

if [ -z "$GENERIF_BASIC" ]; then
	GENERIF_BASIC=$DEFAULT_DOWNLOAD_DIR/generifs_basic.gz
	echo "Using default generifs_basic.gz file at $GENERIF_BASIC"
else
	echo "Using given generifs_basic.gz file at $GENERIF_BASIC"
fi
if [ -z "$GENE2GO" ]; then
	GENE2GO=$DEFAULT_DOWNLOAD_DIR/gene2go.gz
	echo "Using default gene2go.gz file at $GENE2GO"
else
	echo "Using given gene2go.gz file at $GENE2GO"
fi
if [ -z "$UNIPROT_GOA" ]; then
	UNIPROT_GOA=$DEFAULT_DOWNLOAD_DIR/goa_uniprot_all.gpa.gz
	echo "Using default goa_uniprot_all.gpa.gz file at $UNIPROT_GOA"
else
	echo "Using given goa_uniprot_all.gpa.gz file at $UNIPROT_GOA"
fi
if [ -z "$GO" ]; then
	GO=$DEFAULT_DOWNLOAD_DIR/gene_ontology.obo.gz
	echo "Using default gene_ontology.obo.gz file at $GO"
else
	echo "Using given gene_ontology.obo.gz file at $GO"
fi
if [ -z "$UNIPROT_XML" ]; then
	UNIPROT_XML=$DEFAULT_DOWNLOAD_DIR/uniprot_sprot.xml.gz
	echo "Using default uniprot_sprot.xml.gz file at $UNIPROT_XML"
else
	echo "Using given uniprot_sprot.xml.gz file at $UNIPROT_XML"
fi
if [ -z "$INTERACTIONS" ]; then
	INTERACTIONS=$DEFAULT_DOWNLOAD_DIR/interactions.gz
	echo "Using default interactions.gz file at $INTERACTIONS"
else
	echo "Using given interactions.gz file at $INTERACTIONS"
fi
if [ -z "$FULL_GENE_INFO" ]; then
	FULL_GENE_INFO=$DEFAULT_DOWNLOAD_DIR/gene_info.gz
	echo "Using default full gene_info.gz file at $FULL_GENE_INFO"
else
	echo "Using given full gene_info.gz file at $FULL_GENE_INFO"
fi
if [ -z "$GENE_ASN1" ]; then
	GENE_ASN1=$DEFAULT_DOWNLOAD_DIR/All_Data.ags.gz
	echo "Using default gene ASN.1 file at $GENE_ASN1"
else
	echo "Using given gene ASN.1 file at $GENE_ASN1"
fi

# The list of organisms that is used as a filter. May be adapted if a specific set of organisms
# is required.
ORGANISM_LIST=$3
# We employ an organism-filtered gene_info file produced by the makeGeneDictionary script.
# We fall back to the original gene_info if the file isn't given.
GENE_INFO=$4
if [ -z "$GENE_INFO" ]; then
	GENE_INFO=$FULL_GENE_INFO
	echo "Organism filtered gene_info file wasn't given, falling back to $GENE_INFO"
fi
GENE_META_CACHING_DIR=$5
GENE2XML=$6


if [ ! -f eg2designation ] || [ ! -f up2designation ]; then
	echo "make eg and up designations ...";
	echo "cut -f2,14 $GENE_INFO > eg2designation"
	gzip -dc $GENE_INFO | cut -f2,14 > eg2designation_intermediate;
	grep -v "\-$" eg2designation_intermediate > t;
	mv t eg2designation_intermediate;


	if [ ! -f up2designation ]; then
		echo "make reversed version: designation2eg"
		sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' eg2designation_intermediate > designation2eg;
		echo "make uniprot version of designation maps"
		#perl eg2up_replacer.pl up2eg.map designation2eg 0 > designation2up;
		./idreplacer.sh designation2eg 1 up2eg.map 1 > designation2up
		sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' designation2up > up2designation;
		sort -u up2designation > t;
		mv t up2designation;
		sed -e 's/|/\t/g' up2designation | sort -u > t;
		perl makeTerm2Id.pl t > designation2up;
		sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' designation2up > up2designation;
	else
		echo "uniprot designation maps already exist and are not created again"
	fi

	perl makeTerm2Id.pl eg2designation_intermediate > designation2eg;
	sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' designation2eg > t;
	sort -u t > eg2designation;
else
	echo "eg and up designation maps already exist and are not created again"
fi

gzip -dc $GENERIF_BASIC | cut -f2,5 > eg2generif;


if [ ! -f up2go ] || [ ! -f eg2go ]; then
	# extract the gene ID and GO ID columns
	gzip -dc $GENE2GO | cut -f2,3 > eg2go.full;
	# Filter for EG IDs we're actually using
	awk -v idlist=eg.ids 'BEGIN{ FS="\t"; OFS="\t"; while(( getline line<idlist) > 0) ids[line] = 1; }  { if ($1 in ids) { print $0 } }' eg2go.full > eg2go
	./idreplacer.sh eg2go 0 up2eg.map 1 up2go
	
	# faessler: note that the GO association files vary relatively frequently over time; if something goes
	# wrong here, check if the file structure is still correct. All we want here are the GO ID for each UniProt ID
	gzip -dc $UNIPROT_GOA | grep '^UniProtKB' | awk -v idFile=up.ids 'BEGIN{ FS="\t"; OFS="\t"; while(( getline line<idFile) > 0) { ids[line] = 1; } }  { if ($2 in ids) { print $2,$4 } }' > up2goa;
	./idreplacer.sh up2goa 0 up2eg.map 0 eg2goa
	
	echo "Merging GO and GOA mappings"
	cat eg2go eg2goa > t;
	mv t eg2go;
	cat up2go up2goa > t;
	mv t up2go;
else
	echo "up2go and eg2go files already exist and are not created again"
fi

if [ ! -f go_all ]; then

	java -cp gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.GOExport $GO;
	cat cellular_component biological_process molecular_function | sort -u > go_all;
else
	echo "file go_all already exists and is not created again"
fi

if [ ! -f up2summary ]; then
	# This step actually DOES download information from the internet through the e-utils.
	# We query the gene XML information which contains a lot that is not included in gene_info, for instance.
	# Here, we are interested in the summary associated with a gene. We want to add it to our "gene knowledge" for 
	# text context comparison.
	# The called class extracts all information we need from the XML for all steps, e.g. also the protein information used in the dictionary script.
	# Because of this, a similar step is included in the dictionary generation script.

	#echo "Calling de.julielab.jules.ae.genemapping.resources.GeneXMLDownloader to extract the file eg2summary-genexmldownloader.gz from gene XML."
	echo "Calling de.julielab.jules.ae.genemapping.resources.GeneXMLFromASN1Extractor to extract the file eg2summary-genexmldownloader.gz from ASN.1 file $GENE_ASN1."
	#java -cp .:gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.GeneXMLDownloader $ORGANISM_LIST $GENE_META_CACHING_DIR $EMAIL $FULL_GENE_INFO
	java -cp .:gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.GeneXMLFromASN1Extractor $ORGANISM_LIST $GENE_META_CACHING_DIR $GENE_ASN1 $GENE2XML

	exitcode=$?;
	if [[ $exitcode != 0 ]]; then
	        echo "Non-0 exit value when running gene XML download"
	        exit $exitcode
	fi
	echo "making uniprot version of summary file"
	gzip -dc $GENE_META_CACHING_DIR/eg2summary-genexmldownloader.gz | awk -v idfile=eg.ids '
	BEGIN {
		FS="\t";
		while(( getline line<idfile) > 0)
			ids[line] = 1;
	}
	{
		if ($1 in ids)
			print $1 "\t" $2
	}' | sort -u > eg2summary;
	#sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' eg2summary | sort -u > summary2gene;
	#./idreplacer.sh summary2gene 1 up2eg.map 1 summary2up
	#sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' summary2up | sort -u > up2summary;
	./idreplacer.sh eg2summary 0 up2eg.map 1 up2summary
else
	echo "summary files already exist and are not created again"
fi

if [ ! -f up2chromosome ]; then
	## cut out gene2chromosome and replace with up id:
	echo "cut out gene2chromosome ...";
	gzip -dc $GENE_INFO | cut -f2,8 | grep -v "\-$" | sort -u > gene2chromosome;
	sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' gene2chromosome | sort -u > chromosome2gene;
	./idreplacer.sh chromosome2gene 1 up2eg.map 1 | sort -u > chromosome2up
	sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' chromosome2up | sort -u > up2chromosome;
else
	echo "chromosome files already exist and are not created again"
fi

# The UniprotFreeTextExtractor creates a large range of files some of which are used below. We just test for one here.
if [ ! -f uniprot_id2disease ]; then
	echo "create the uniprot freetext context files ...";
	java -Xmx20G -cp gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.UniprotFreeTextExtractor $UNIPROT_XML up2eg.map;
else
	echo "uniprot_id2disease already exists and is not created again"
fi

if [ ! -f up2generif ]; then
	## create up2generif file:
	echo "create up2generif file ...";
	sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' eg2generif | sort -u > generif2eg;
	./idreplacer.sh generif2eg 1 up2eg.map 1 | sort -u > generif2up
	sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' generif2up | sort -u > up2generif;
else
	echo "file up2generif already exists and is not created again"
fi

if [ ! -f up2freetext_chromo.context ] || [ ! -f eg2freetext_chromo.context ]; then
	## make uniprot freetext context
	echo "make uniprot freetext context ...";
	# Note that at some time, UniProt renamed 'enzyme regulation' to 'activity regulation': https://www.uniprot.org/help/enzyme_regulation
	# Here we use the renamed version. Thus this script wont work with older versions of UniProt.
	cat uniprot_id2developmental\ stage uniprot_id2disease uniprot_id2domain uniprot_id2activity\ regulation uniprot_id2function uniprot_id2induction uniprot_id2keyword uniprot_id2miscellaneous uniprot_id2pathway uniprot_id2similarity uniprot_id2subcellular\ location uniprot_id2subunit uniprot_id2tissue\ specificity > up.freetext;
	cat up.freetext up2chromosome | sort -u > up2freetext_chromo.context;
	rm up.freetext;

	## make uniprot freetext context
	echo "make entrez freetext context ...";
	cat entrezgene_id2developmental\ stage entrezgene_id2disease entrezgene_id2domain entrezgene_id2activity\ regulation entrezgene_id2function entrezgene_id2induction entrezgene_id2keyword entrezgene_id2miscellaneous entrezgene_id2pathway entrezgene_id2similarity entrezgene_id2subcellular\ location entrezgene_id2subunit entrezgene_id2tissue\ specificity > eg.freetext;
	cat eg.freetext gene2chromosome | sort -u > eg2freetext_chromo.context;
	rm eg.freetext;
else
	echo "eg and up freetext files already exist and are not created again"
fi

if [ ! -f interaction2eg ] || [ ! -f up2interaction ]; then
	gzip -dc $INTERACTIONS | cut -f2,16 | sort -u > eg2interaction;
	grep -v -f interact.filter eg2interaction > t;
	sed -e 's/^.*\tin vi.*$//g' t > tt;
	sed -e 's/^.*\t-$//g' tt | sort -u > ttt;
	grep -v "^$" ttt > eg2interaction;
	sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' eg2interaction | sort -u > interaction2eg;
	./idreplacer.sh interaction2eg 1 up2eg.map 1 interaction2up
	sed -e 's/^\(.*\)\t\(.*\)$/\2\t\1/g' interaction2up | sort -u > up2interaction;
else
	echo "eg and up interaction files already exist and are not created again"
fi

echo "cp up2designation $2;"
cp up2designation $2;
echo "cp up2freetext_chromo.context $2;"
cp up2freetext_chromo.context $2;
echo "cp go_all $2;"
cp go_all $2;
echo "cp up2go $2;"
cp up2go $2;
echo "cp up2summary $2;"
cp up2summary $2;
echo "cp up2generif $2;"
cp up2generif $2;
echo "cp up2interaction $2;"
cp up2interaction $2;

echo "cp eg2designation $2;"
cp eg2designation $2;
echo "cp eg2freetext_chromo.context $2;"
cp eg2freetext_chromo.context $2;
echo "cp go_all $2;"
cp go_all $2;
echo "cp gene2go $2;"
cp eg2go $2;
echo "cp eg2summary $2;"
cp eg2summary $2;
echo "cp eg2generif $2;"
cp eg2generif $2;
echo "cp eg2interaction $2;"
cp eg2interaction $2;
