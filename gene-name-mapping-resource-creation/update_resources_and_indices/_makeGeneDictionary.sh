# Parameters:
# $1: external resources directory
# $2: final dictionaries and ID lists output directory
# $3: organism list with NCBI Taxonomy IDs to restrict dictionary creation to
# $4: file where to store the organism filtered gene_info file should be stored for later use
# $5: directory where downloaded NCBI Gene XML files can be stored to or read from, if already existing
# $6: optional list of gene IDs to restrict dictionary generation to.
# This script requires a list of files that originally are downloaded from the
# internet. This is not done in this script. Instead, the default locations
# specified in the downloadExternalResources.sh script are given. The files in question are:
# * gene_info
# * idmapping.tb
# * uniprot_sprot.dat
# * uniprot_sprot.xml
# * bioThesaurus.dist_7.0
# * iproclass.tb
# In addition, the script requires EC Numbers derived from BioConductor resources by the BioC scripts:
# * EntrezGene_*.txt
# * UniProt_*.txt
# The sole purpose of all files for this script is to deliver as many names and synonyms for
# NCBI Gene and UniProt IDs as possible for maximum coverage of gene names.

DEFAULT_DOWNLOAD_DIR=$1

if [ -z "$GENE_INFO" ]; then
	GENE_INFO=$DEFAULT_DOWNLOAD_DIR/gene_info.gz
	echo "Using default gene_info file at $GENE_INFO"
else
	echo "Using given gene_info file at $GENE_INFO"
fi
if [ -z "$GENE_ASN1" ]; then
	GENE_ASN1=$DEFAULT_DOWNLOAD_DIR/All_Data.ags.gz
	echo "Using default gene ASN.1 file at $GENE_ASN1"
else
	echo "Using given gene ASN.1 file at $GENE_ASN1"
fi
if [ -z "$IDMAPPING" ]; then
	IDMAPPING=$DEFAULT_DOWNLOAD_DIR/idmapping.tb.gz
	echo "Using default idmapping.tb.gz file at $IDMAPPING"
else
	echo "Using given idmapping.tb.gz file at $IDMAPPING"
fi
if [ -z "$UNIPROT_DAT" ]; then
	UNIPROT_DAT=$DEFAULT_DOWNLOAD_DIR/uniprot_sprot.dat.gz
	echo "Using default uniprot_sprot.dat.gz file at $UNIPROT_DAT"
else
	echo "Using given uniprot_sprot.dat.gz file at $UNIPROT_DAT"
fi
if [ -z "$UNIPROT_XML" ]; then
	UNIPROT_XML=$DEFAULT_DOWNLOAD_DIR/uniprot_sprot.xml.gz
	echo "Using default uniprot_sprot.xml.gz file at $UNIPROT_XML"
else
	echo "Using given uniprot_sprot.xml.gz file at $UNIPROT_XML"
fi
if [ -z "$BIO_THESAURUS" ]; then
	BIO_THESAURUS=$DEFAULT_DOWNLOAD_DIR/bioThesaurus.dist_7.0.gz
	echo "Using default bioThesaurus.dist_7.0.gz file at $BIO_THESAURUS"
else
	echo "Using default bioThesaurus.dist_7.0.gz file at $BIO_THESAURUS"
fi
if [ -z "$IPROCLASS" ]; then
	IPROCLASS=$DEFAULT_DOWNLOAD_DIR/iproclass.tb.gz
	echo "Using default iproclass.tb.gz file at $IPROCLASS"
else
	echo "Using given proclass.tb.igz file at $IPROCLASS"
fi
if [ -z "$BIOC" ]; then
	BIOC=$DEFAULT_DOWNLOAD_DIR
	echo "Using default BioConductor resources files directory at $BIOC"
else
	echo "Using given BioConductor resources files directory at $BIOC"
fi


# The list of organisms that is used as a filter. May be adapted if a specific set of organisms
# is required.
ORGANISM_LIST=$3
GENE_INFO_ORG_FILTERED=$4
GENE_META_CACHING_DIR=$5
GENE2XML=$6
GENE_IDS_TO_INCLUDE=$7
GENE_ID_LIST=$8


# Used for sort for large files
CONCURRENCY_LEVEL=20


# Check if the organism file exists and if so, if it has any contents
if [[ ! -f $ORGANISM_LIST ]] || [[ $(echo -e $(wc -c < "$ORGANISM_LIST")) == 0 ]]; then
    echo "No organism filter given. Filling file $ORGANISM_LIST with all species in gene_info for following steps"
    gzip -dc $GENE_INFO | cut -f1 | sort -u > $ORGANISM_LIST;
fi

# We query the gene XML information which contains a lot that is not included in gene_info, for instance.
# Here, we are interested in the protein names associated with a gene. We want to add them to the dictionary.
# Also, we extract the RefSeq status of each gene and use this information to filter out gene IDs that have a very
# uncertain status (like it could exist because it was found by, e.g. an automated BLAST analysis, but noone has ever checked if this
# is actually right). gene_info also includes a large number of withdrawn or replaced items which we also want to exclude.
# Refer to https://www.ncbi.nlm.nih.gov/refseq/about/ for more information.
# The called class extracts all information we need from the XML for all steps, e.g. also the gene2summary file.
# Because of this, a similar step is included in the context index generation script.
# The java programs checks if updates are necessary.
echo "Calling de.julielab.jules.ae.genemapping.resources.GeneXMLFromASN1Extractor to extract the files eg2entrezgene_prot-genexmldownloader.gz and eg2refseq_genetrack_status-genexmldownloader.gz from ASN.1 file $GENE_ASN1."
java -cp .:gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.GeneXMLFromASN1Extractor $ORGANISM_LIST $GENE_META_CACHING_DIR $GENE_ASN1 $GENE2XML
if [[ 0 -ne "$?" ]]; then
    echo "Error occured, exiting".
    exit 1
fi

echo "creating gene dictionary for organisms (taxid)";

if [ ! -f "$GENE_INFO_ORG_FILTERED" ]; then
    # The filtering is actually more than just organisms. We here filter according to:
    # 1. Organisms
    # 2. NCBI Gene RefSeq status
    # 3. Withdrawn or replaced entries
    # 4. Uncharacterized entries which don't have a propert symbol (apart from "LOC16190849" for example)
    # 5. New entries that also don't have a name apart from "NEWENTRY"
    # 6. Hypothetical proteins like CHC_T00000060001, gene ID 17325531
    # 7. Putative proteins like Tsp_05578, gene ID 10913376

    echo "Filtering gene_info for the organisms given in ${ORGANISM_LIST} and also filtering out genes that have been withdrawn or replaced or are just hypothetically present."
    # -nt: "newer than"
    if [ "$GENE_META_CACHING_DIR/eg2refseq_genetrack_status-genexmldownloader.gz" -nt "$GENE_META_CACHING_DIR/eg2refseq_genetrack_status-genexmldownloader" ]; then
        echo "Decompressing $GENE_META_CACHING_DIR/eg2refseq_genetrack_status-genexmldownloader.gz for use in AWK script."
        if [ -f "$GENE_META_CACHING_DIR/eg2refseq_genetrack_status-genexmldownloader" ]; then
            rm "$GENE_META_CACHING_DIR/eg2refseq_genetrack_status-genexmldownloader";
        fi
        gunzip $GENE_META_CACHING_DIR/eg2refseq_genetrack_status-genexmldownloader.gz;
    fi
    echo "Doing the actual filtering and writing the result to $GENE_INFO_ORG_FILTERED"
    gzip -dc $GENE_INFO | awk -v orgfile=$ORGANISM_LIST -v statusfile=$GENE_META_CACHING_DIR/eg2refseq_genetrack_status-genexmldownloader -v idstoinclude=$GENE_IDS_TO_INCLUDE '
    BEGIN {
        print "Reading organism file and creating the set of eligible organisms for filtering" > "/dev/stderr"
        FS="\t";
        # Build the set of organisms
        while(( getline line<orgfile) > 0)
            orgs[line] = 1;
        print "Got", length(orgs), "filter organisms" > "/dev/stderr"
        # Build the set of genes with an accepted gene Track and RefSeq status
        print "Reading the NCBI Gene records status file for excluding items with unwanted status (replaced, deprecated)" > "/dev/stderr"
        while(( getline line<statusfile) > 0) {
            split(line, array, FS);
            accept = 1;
            refseqStatus = tolower(array[2]);
            geneTrackStatus = array[4];
            # refseqStatus == "inferred" || commentented out because those items were actually required/used by project partners
            #if (refseqStatus == "predicted")
             #   accept = 0;
            if (geneTrackStatus > 0)
                accept = 0;
            if (accept)
                accepted[array[1]] = accept;
        }
        print "Loading gene IDs that should be included in any case." > "/dev/stderr"
        while(( getline line<idstoinclude) > 0)
           toinclude[line] = 1;
        print "Done reading filter files." > "/dev/stderr"
    }
    {
        istobeincluded = $2 in toinclude;
        isputative = match($9, /^putative.*protein$/);
        ishypothetical = $9 == "hypothetical protein";
        isnewentry = $3 == "NEWENTRY";
        isaccepted = $2 in accepted;
        hasrequestedorg = $1 in orgs;


        if (istobeincluded || (!isputative && !ishypothetical && !isnewentry && hasrequestedorg && isaccepted)) {
            print $0
        }
    }' | gzip > $GENE_INFO_ORG_FILTERED
    echo "Done."
else
	echo "$GENE_INFO_ORG_FILTERED already exists and is not created again."
fi


# This step may be used to restrict the dictionaries and indexes (if metaScript.sh is used) to a specific
# set of IDs that are possibly further refined by organism and time stamp. This is only done for experiments
# or when you know very specificly which gene IDs you need.
if [ ! -z "$GENE_ID_LIST" ]; then
	echo "Restricting gene_info to gene IDs in $GENE_ID_LIST"
	gzip -dc $GENE_INFO_ORG_FILTERED | awk -v idfile=$GENE_ID_LIST '
	BEGIN {
		FS="\t";
		while(( getline line<idfile) > 0)
			ids[line] = 1;
	}
	{
		if ($2 in ids)
			print $0
	}' | gzip > gene_info_id_filtered.tmp
	mv gene_info_id_filtered.tmp $GENE_INFO_ORG_FILTERED
else
	echo "No restriction to gene IDs is applied"
fi

if [ ! -f eg.ids ]; then
    echo "cut -f2 $GENE_INFO_ORG_FILTERED | sort -u > eg.ids"
    gzip -dc $GENE_INFO_ORG_FILTERED | cut -f2 | sort -u > eg.ids;
else
    echo "eg.ids file already exists and is not created again."
fi

# CREATING A DICTIONARY ONLY FROM gene_info FIELDS
if [ ! -s entrez_gene.dict.eg ]; then
	# 2: GeneID, 3: Symbol, 4: LocusTag, 5: Synonyms, 6: dbXrefs, 7: chromosome, 8: map_location, 11: Symbol_from_nomenclature_authority, 12: Full_name_from_nomenclature_authority, 14: Other_designations
	# Priorities:
	# P1: 3 - Symbol, 6 - dbXrefs, 11 - Symbol_from_nomenclature_authority, 12 - Full_name_from_nomenclature_authority
	# P2: 5 - Synonyms
	# P3: 14 - Other_designations (those seem to be the protein names)
	gzip -dc $GENE_INFO_ORG_FILTERED | cut -f2,3 > ts
	gzip -dc $GENE_INFO_ORG_FILTERED | cut -f2,11 > tn
	gzip -dc $GENE_INFO_ORG_FILTERED | cut -f2,6,12 > t1
	gzip -dc $GENE_INFO_ORG_FILTERED | cut -f2,5         > t2
	gzip -dc $GENE_INFO_ORG_FILTERED | cut -f2,14        > t3

	echo "Creating primary, secondary and tertiary name lists from $GENE_INFO_ORG_FILTERED"
	cat ts | tr '|' '\t' > tts
	cat tn | tr '|' '\t' > ttn
	cat t1 | tr '|' '\t' > tt1
	cat t2 | tr '|' '\t' > tt2
	cat t3 | tr '|' '\t' > tt3
	perl makeTerm2Id.pl tts > ts
	perl makeTerm2Id.pl ttn > tn 
	perl makeTerm2Id.pl tt1 > t1
	perl makeTerm2Id.pl tt2 > t2
	perl makeTerm2Id.pl tt3 > t3
	
	cat ts | awk '{print $1,$2,"-1"}' FS="\t" OFS="\t" | sort -u | gzip > names2egsymbols.gz
	cat tn | awk '{print $1,$2,"0"}' FS="\t" OFS="\t" | sort -u | gzip > names2egnomclat.gz
	cat t1 | awk '{print $1,$2,"1"}' FS="\t" OFS="\t" | sort -u | gzip > names2egprimary.gz
	cat t2 | awk '{print $1,$2,"2"}' FS="\t" OFS="\t" | sort -u | gzip > names2egsecondary.gz
	cat t3 | awk '{print $1,$2,"3"}' FS="\t" OFS="\t" | sort -u | gzip > names2egtertiary.gz
	rm t1 t2 t3 tt1 tt2 tt3 ts tn
	
	#echo "sed -e 's/|/\t/g' gene_info.names > gene_info.names.t";
	# The synonyms are separated by a pipe (|), replace with tabs
	#sed -e 's/|/\t/g' gene_info.names > gene_info.names.t;

	#echo "perl makeTerm2Id.pl gene_info.names.t > entrez_gene.dict"
	# Breaks the tab-separated name fields into lines
	#perl makeTerm2Id.pl gene_info.names.t > entrez_gene.dict;

	echo "gzip -dc names2egprimary.gz names2egsecondary.gz names2egtertiary.gz > entrez_gene.dict.eg"
	gzip -dc names2egsymbols.gz names2egnomclat.gz names2egprimary.gz names2egsecondary.gz names2egtertiary.gz > entrez_gene.dict.eg
	#echo "sort -u entrez_gene.dict > entrez_gene.dict.unique";
	#sort -u entrez_gene.dict > entrez_gene.dict.unique;
	#mv entrez_gene.dict.unique entrez_gene.dict.eg;
	#rm gene_info.names.t;

	echo "Append eg2entrezgene_prot-genexmldownloader.gz names to the dictionary"
	gzip -dc $GENE_META_CACHING_DIR/eg2entrezgene_prot-genexmldownloader.gz | awk -v idfile=eg.ids '
	BEGIN {
		FS="\t";
		while(( getline line<idfile) > 0)
			ids[line] = 1;
	}
	{
		if ($1 in ids)
			print $2 "\t" $1
	}' | sed 's/.*/&\t4/' >> entrez_gene.dict.eg;
#	awk 'BEGIN{FS="\t"}{print $2 "\t" $1}' eg2entrezgene_prot-genexmldownloader.gz >> entrez_gene.dict.eg;
else
	echo "file entrez_gene.dict already exists and is not created again"
fi
# DONE WITH DICTIONARY FROM gene_info

if [ ! -s up2eg2tax.map ]; then
	echo "creating up2eg2tax.map..."
	gzip -dc $IDMAPPING | awk -v orgfile=$ORGANISM_LIST '
	BEGIN {
		FS="\t";
		while(( getline line<orgfile) > 0)
			orgs[line] = 1;
	}
	{
		if ($16 in orgs)
			print $1 "\t" $3 "\t" $16
	}' | sort -u > up2eg2tax.map
else
	echo "up2eg2tax.map already exists and is not created again."
fi

if [ ! -s up.ids ]; then
	echo "Extracting UniProt accession IDs, UniProt mnemonic IDs and taxonomy IDs from $UNIPROT_XML to create up.ids"
	# UniProt entries may have multiple accession ID due to merging or splitting of entries (see http://www.uniprot.org/help/accession_numbers).
	# The accession xPath "accession[0]" only selects the first accession element which corresponds to the primary accession (http://www.uniprot.org/format/uniprotkb_ids_and_dates
	java -cp gene-name-mapping-resource-creation.jar de.julielab.xml.JulieXMLToolsCLIRecords $UNIPROT_XML /uniprot/entry accession[1] name "organism/dbReference[@type='NCBI Taxonomy']/@id" > t
	awk -v idlist=$ORGANISM_LIST 'BEGIN{ FS="\t"; while(( getline line<idlist) > 0) ids[line] = 1; }  { if ($3 in ids) print $1 }' t  > up.ids
else
	echo "up.ids file does already exist and is not created again."
fi

if [ ! -s uniprot.dict ]; then
	echo "make uniprot.dict out of xml ...";
	java -cp gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.UniprotDictCreator $UNIPROT_XML uniprot.all.dict;

	# filter the complete UniProt dictionary by the organism filtered ID list we created above
	awk -v idlist=up.ids 'BEGIN{ FS="\t"; while(( getline line<idlist) > 0) ids[line] = 1; }  { if ($2 in ids) print $0 }' uniprot.all.dict > uniprot.dict
else
	echo "uniprot.dict already exists and is not created again."
fi


# create uniprot 2 entrezGene map:
# NOTE: The resulting up2eg.map might have multiple entrez gene IDs for one UniProt ID. The idmapping file,
# from which the original data cames (just see further above) sometimes lists multiple EG IDs separated
# by semicoli. We split them (see below) and create a new line with the same UniProt ID. Thus, eventually
# UniProt IDs may be duplicated across lines! Entrez Gene IDs will be unique.
if [ ! -s up2eg.map ]; then
	echo "Creating up2eg.map..."
	cut -f1,2 up2eg2tax.map > up2eg.map;
	awk -F"\t" '{split($2,egids,";"); for(i in egids){ gsub(/ /, "", egids[i]); print $1 "\t" egids[i]; } }' up2eg.map > t
	grep -v "[[:space:]]$" t | sort -u > up2eg.map;
	rm t;
else
	echo "File up2eg.map already exists and is not created again."
fi

# only take those up2eg which are in the (up-reviewed and organism filtered) up.ids
echo "only take those up2eg which are in the (up-reviewed and organism filtered) up.ids ...";
awk -v uniprotids=up.ids '
	BEGIN {
		FS="\t";
		while(( getline line<uniprotids) > 0)
			ids[line] = 1;
	}
	{
		if ($1 in ids)
			print $0
	}' up2eg.map > t
sort -u t > up2eg.map;

if [ ! -f entrez_gene.dict.up ]; then
    echo "replace entrez_gene ids with uniprot ids in entrez_gene.dict ...";
    ./idreplacer.sh entrez_gene.dict.eg 1 up2eg.map 1 entrez_gene.dict.up
else
    echo "entrez_gene.dict.up already exists and is not created again."
fi


if [ ! -s bt_iproclass.orgfiltered.up ]; then
	# We first do the organism filtering of the BioThesaurus and iproclass so the
	# BioThesaurusIproClassMerger has less memory requirements
	echo "Creating organism filtered version of the BioThesaurus"
	# The BioThesaurus is a bit complicated because its field seperator is ~|~, thus: FS must have the escaped |; OFS (Output Field Seperator)
	# is set to tabs. The $1=$1 makes the fields be changed from FS to OFS.
	gzip -dc $BIO_THESAURUS | awk -v idlist=up.ids 'BEGIN{ FS="~\\|~"; OFS="\t"; while(( getline line<idlist) > 0) ids[line] = 1; }  { if ($1 in ids) { $1=$1; print $0 } }' > bioThesaurus.orgfiltered
	echo "Creating organism filtered version of iproclass"
	gzip -dc $IPROCLASS | awk -v idlist=up.ids 'BEGIN{ FS="\t"; while(( getline line<idlist) > 0) ids[line] = 1; }  { if ($1 in ids) print $0 }' > iproclass.orgfiltered
	# We just use iproclass - which is basically a huge mapping file - to append a few IDs, namely:
	# col 2:  UniProtKB ID
	# col 12: UniRef90
	# col 13: UniRef50
    # col 16: NCBI taxonomy
    # The following tool just does a join operation of the  BioThesaurus and iproclass files on UniProt accession ID
    echo "Joining organism filtered BioThesaurus and iproclass on UniProt accession ID..."
	java -cp .:gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.BioThesaurusIproClassMerger bioThesaurus.orgfiltered iproclass.orgfiltered bt_iproclass.orgfiltered.up true;

else
	echo "bt_iproclass.orgfiltered.up already exists and is not created again."
fi

if [ ! -f bt2up.dict ]; then
    # Extract the UniProt ID and its synonym in the BioThesaurus
    awk -F"\t" -v OFS="\t" '{print $2,$1}' bt_iproclass.orgfiltered.up > bt2up.dict
    sed 's/.*/&\t7/' bt2up.dict  > bt2up.dict.prio
    ./idreplacer.sh bt2up.dict 1 up2eg.map 0 t
    awk -F"\t" -v OFS="\t" -v idlist=eg.ids '{while(( getline line<idlist) > 0) ids[line] = 1;}{ if ($2 in ids) print $0 }' t > bt2eg.dict
else
    echo "b2up.dict already exists and is not created again."
fi

if [ ! -s bioc.dict.up ] || [ ! -s bioc.dict.eg ]; then
	# Extract the gene ID and the synonyms; there might be multiple synonyms per ID separated by semicolon
	cat $BIOC/EntrezGene* | awk -F"\t" -v OFS="\t" '{split($3, syns, ";"); for (i in syns) print syns[i], $1; }' > t
	# Now extract the enzyme commission numbers (they look like 1.1.99.1) but append them to the string "EC " because in text most of the time the "EC" prefix occurs
	cat $BIOC/EntrezGene* | awk -F"\t" -v OFS="\t" '{split($2, ecs, ";"); for (i in ecs) print "EC " ecs[i], $1; }' > tt
	# Those two information taken together form our BioConductor dictionary for NCBI Gene, after filtering for the gene IDs in our current scope (mostly or entirely due to organism filtering)
	cat t tt | awk -F"\t" -v OFS="\t" -v idlist=eg.ids '{while(( getline line<idlist) > 0) ids[line] = 1;}{ if ($2 in ids) print $0 }' > bioc.dict.eg

	# Repeat for UniProt. However, there might be multiple UniProt IDs per entry (probably the orginal authors just mapped from gene ID to UniProt), we also need to take care of that
	# Thus, in the next line we split on IDs and on synonyms and use two for loops to create all combinations
	cat $BIOC/UniProt* | awk -F"\t" -v OFS="\t" '{split($1, ids, ";"); split($3, syns, ";"); for (i in ids) for (j in syns) print syns[j], ids[i]; }' > t
	# Now extract the enzyme commission numbers (they look like 1.1.99.1) but append them to the string "EC " because in text most of the time the "EC" prefix occurs
	cat $BIOC/UniProt* | awk -F"\t" -v OFS="\t" '{split($1, ids, ";"); split($2, ecs, ";"); for (i in ids) for (j in ecs) print "EC " ecs[j], ids[i]; }' > tt
	# Those two information taken together form our BioConductor dictionary for UniProt, after filtering for the gene IDs in our current scope (mostly or entirely due to organism filtering)
	cat t tt | awk -F"\t" -v OFS="\t" -v idlist=up.ids '{while(( getline line<idlist) > 0) ids[line] = 1;}{ if ($2 in ids) print $0 }' > bioc.dict.up
	sed 's/.*/&\t6/' bioc.dict.up > bioc.dict.up.prio
else
	echo "BioConductor derived dictionaries already exist and are not created again"
fi


echo "FINALLY: cat and unique the four (gene_info, UniProt, BioThesausus, BioConductor) dictionaries ...";
# TODO investigate what resource these eg_parens have been originally and if we could use them
### engelmann: leaving out eg_parens.eg for the moment
### perl eg2up_replacer.pl up2eg.map eg_parens.eg 0 | sort -u > eg_parens.up;
if [ ! -f gene.dict.up ]; then
	cat entrez_gene.dict.up bt2up.dict.prio uniprot.dict bioc.dict.up.prio > t;
	cat t | sort -u > gene.dict.up;
	# Pattern cleaner
	perl DictCleaner.pl gene.dict.up > t;
	# Also cleaning with lists and other stuff
	java -cp .:gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.DictionaryGeneralFilter t tt;
	mv tt t;
	cat t | sort -u > gene.dict.up;
	rm t;
else
	echo "gene.dict.up already exists and is not created again"
fi

if [ ! -f gene.dict.eg ]; then
    echo "Creating gene.dict.eg and gene.dict.variants.norm.eg"
    echo "A concurrencly level of $CONCURRENCY_LEVEL will be used for the sort program via the --parallel switch"
    # The --parallel switch does not come into effect when the sort program is fed from a pipe. It must be
    # called directly on a file to take advantage of multiple CPUs
    # Map the UniProt dictionary to gene IDs and then filter the result with the eligible gene ID list
	./idreplacer.sh uniprot.dict 1 up2eg.map 0 > t
	# This is the ID filtering
	awk -F"\t" -v OFS="\t" -v idlist=eg.ids '{while(( getline line<idlist) > 0) ids[line] = 1;}{ if ($2 in ids) print $0 }' t > tt

	echo "Assigning synonym priority values to synonyms from UniProt (prio 5), BioC (prio 6) and BioThesaurus (prio 7) dictionaries"
	sort --parallel=$CONCURRENCY_LEVEL -u tt | sed 's/.*/&\t5/' > uniprot.dict.eg.prio;
	sed 's/.*/&\t6/' bioc.dict.eg > bioc.dict.eg.prio
	sed 's/.*/&\t7/' bt2eg.dict   > bt2eg.dict.prio

	echo "Cat'ing together the Entrez Gene, UniProt, BioC and BioThesaurus dictionaries to form the first version of gene.dict.eg"
	cat entrez_gene.dict.eg uniprot.dict.eg.prio bt2eg.dict.prio bioc.dict.eg.prio > t
	sort --parallel=$CONCURRENCY_LEVEL -u t > gene.dict.eg;
	# echo "Running the TermNormalizer on the current gene.dict.eg file to create the first version of gene.dict.variants.norm.eg"
    java -cp .:gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.utils.norm.TermNormalizer gene.dict.eg gene.dict.variants.norm.eg;
	sort --parallel=$CONCURRENCY_LEVEL -t$'\t' -k3 -n gene.dict.eg > t
	sort --parallel=$CONCURRENCY_LEVEL -t$'\t' -k3 -n gene.dict.variants.norm.eg > t2
	# Sort by priority. The next step will be to eliminate duplicates. Only the first occurrence of a name will
	# be kept. Thus, if a name happens to occur in higher and lower priority, the higher priority will be kept.
	echo "Filtering the synonyms of both *.eg dictionaries to only keep the highest priority item of each synonym"
	awk '{if (!($1$2 in a)) {a[$1$2]=1;print $0}}' FS="\t" t > tt
	awk '{if (!($1$2 in a)) {a[$1$2]=1;print $0}}' FS="\t" t2 > tt2
	# Pattern cleaner
	echo "Filtering both dictionaries for non-specific synonyms"
	perl DictCleaner.pl tt > t;
	perl DictCleaner.pl tt2 > t2;
	# Also cleaning with lists and other stuff
	java -cp .:gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.DictionaryGeneralFilter t tt;
	java -cp .:gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.DictionaryGeneralFilter t2 tt2;
	mv tt t;
	mv tt2 t2;
	# This is sometimes required to get the same synonyms to be grouped together. With other locales, e.g.
	# en_GB.UTF-8, something like this happens:
	# 101 k da malaria antigenlike    109591564       3
    # 101 k da malaria antigen like   109886148       3
    # 101 k da malaria antigenlike    109886148       3
    # 101 k da malaria antigen like   109946599       3
    # Which we cannot have because some subsequent algorithms (NameCentricSynonymIndexGenerator) require
    # the C-locale ordering.
	export LC_ALL=C
	echo "Sorting and unifying both dictionaries to create the final gene.dict.eg and gene.dict.variants.norm.eg files"
	sort --parallel=$CONCURRENCY_LEVEL -u t  > gene.dict.eg;
	sort --parallel=$CONCURRENCY_LEVEL -u t2 > gene.dict.variants.norm.eg;

	echo "Filtering the gene.dict.variants.norm.eg dictionary to create gene.dict.variants.norm.filtered.eg"
	java -cp .:gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.DictionaryFamilyDomainFilter gene.dict.variants.norm.eg t2;
	sort --parallel=$CONCURRENCY_LEVEL -u t2 > gene.dict.variants.norm.filtered.eg;
	echo "Generating Lingpipe dictionary from the gene.dict.variants.norm.filtered.eg dictionary. This is used to collect information about each synonym in PubMed."
	awk '{print $1 "\t" $2}' FS="\t" gene.dict.variants.norm.filtered.eg > gene.dict.variants.norm.filtered.lingpipe
	rm t;
	rm t2;
else
	echo "gene.dict.eg already exists and is not created again"
fi


echo "cp uniprot.ids $2"
cp up.ids $2
echo "cp gene.dict.up $2"
cp gene.dict.up $2
echo "cp eg.ids $2"
cp eg.ids $2
echo "cp gene.dict.eg $2"
cp gene.dict.eg $2
echo "cp gene.dict.variants.norm.eg $2"
cp gene.dict.variants.norm.eg $2
echo "cp gene.dict.variants.norm.filtered.eg $2"
cp gene.dict.variants.norm.filtered.eg $2
echo "cp gene.dict.variants.norm.filtered.lingpipe $2"
cp gene.dict.variants.norm.filtered.lingpipe $2
echo "cp up2eg2tax.map $2"
cp up2eg2tax.map $2
echo "cp $GENE_INFO_ORG_FILTERED $2"
cp $GENE_INFO_ORG_FILTERED $2
echo "cp $ORGANISM_LIST $2/organisms.taxid"
cp $ORGANISM_LIST $2/organisms.taxid
