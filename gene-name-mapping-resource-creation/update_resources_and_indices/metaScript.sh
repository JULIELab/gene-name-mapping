#!/bin/bash
# TODO: Document
GENE2XML=gene2xmlExecutables/linux64.gene2xml
# This is the directory where all external resources have been stored.
# Must be absolute or relative to the position where metaScript.sh is
# called.
DOWNLOAD_DIR=external-resources-download/download
# This is the top script to call for JCoRe Gene Mapper resource creation. 
# specify directory of resources and indices here:
resourcesDir=/data/erik/gene-mapping/human
# This is either:
# - A list of NCBI taxonomy IDs to restrict the created gene resources to.
# - A non-existing file that will then be automatically created with ALL
#   taxonomy IDs in gene_info and lead to no restriction on species
ORGANISM_LIST=organisms_human.taxid
# Actually, the file delivered here won't just be organism filtered
# but will be the one file specifying all gene IDs that should
# go into the dictionary and the indexes. It is generated
# automatically in the makeGeneDictionary script and is then
# reused by following steps that require the filtered ID list.
GENE_INFO_ORG_FILTERED=gene_info_organism_filtered.gz
# This directory is used to store some gene meta information that are expensive to get
# like gene summaries that have to be downloaded from NCBI.
GENE_META_CACHING_DIR=$DOWNLOAD_DIR/gene-meta-cache
# Gene IDs in the following list are to be included into the resources even if they would have been filtered out
# otherwise. This must be set to some value for the number of arguments to be correct when calling
# the _makeGeneDictionary.sh script. If the name of a non-existent file is given, an empty file will be created
GENE_IDS_TO_INCLUDE=no_specific_genes_to_include
# This parameter may be used to restrict the dictionaries and indexes to a specific
# set of IDs that are possibly further refined by organism and time stamp. This is only done for experiments
# or when you know very specificly which gene IDs you need. If you just want to create normal resources for
# GeNo, leave the value blank (i.e. the line should look like this: GENE_ID_LIST=)
GENE_ID_LIST=
#GENE_ID_LIST="gene-ids-in-entrezGeneLexicon_index.lst"

if [ -z "$GENE_IDS_TO_INCLUDE" ]; then
    echo "Error: The variable GENE_IDS_TO_INCLUDE must be set to some value to satisfy script parameter numbers. If there are no specific genes to include, provide some name of a file that simply does not exist."
    exit 1
fi

echo "Get current project jar (with dependencies) from ../target/";
if [ ! -f ../target/gene-name-mapping-resource-creation*with-dependencies.jar ];  then
    echo "Could not find project jar in ../target/"
    exit
fi;
cp ../target/gene-name-mapping-resource-creation*with-dependencies.jar .;
mv gene-name-mapping-resource-creation*with-dependencies.jar gene-name-mapping-resource-creation.jar;

if [ -d ${resourcesDir} ]; then
        echo "Warning: The specified directory for resources and indices (${resourcesDir}) already exists!"
        echo "If you want it to be overwritten type \"yes\" and press [ENTER], else \"no\" to end script. Enter \"continue\" to start processing in the existing folders, potentially using intermediate results already computed."
        answerStatus=0
        while [ $answerStatus -eq 0 ]; do
                read answer
                if [[ $answer == "no" ]]; then
                        exit
                elif [[ $answer == "yes" ]]; then
                        answerStatus=1 
                        rm -r ${resourcesDir}
                        mkdir -p ${resourcesDir}
                elif [[ $answer == "continue" ]]; then
                        answerStatus=1
                        echo "Starting processing in existing folder ${resourcesDir}."
                else
                        echo "State yes, no or continue"
                fi
        done
else
        echo "Creating specified directory for resources and indices (${resourcesDir})"
        mkdir -p ${resourcesDir}
fi
# determine the absolute path of the external resources download directory
if [[ ! $DOWNLOAD_DIR = /* ]]; then
	DOWNLOAD_DIR=`pwd`/$DOWNLOAD_DIR
fi
if [[ ! $GENE_META_CACHING_DIR = /* ]]; then
	GENE_META_CACHING_DIR=`pwd`/$GENE_META_CACHING_DIR
fi
if [[ ! $resourcesDir = /* ]]; then
	resourcesDir=`pwd`/$resourcesDir
fi
if [[ ! $res = /* ]]; then
	res=`pwd`/$res
fi
if [[ ! $GENE2XML = /* ]]; then
	GENE2XML=`pwd`/$GENE2XML
fi

# May be used to set environment variables pointing to different resources
# required for the GeNo resources generation scripts.
# This way, the downloaded resources may be placed outside of the default
# locations.
source setCustomResourcePaths.sh

res=${resourcesDir}/resources;
echo "Creating directory $res";
mkdir $res;
synIndex=${resourcesDir}/syn_indices;
echo "Creating directory $synIndex"
mkdir $synIndex;
conIndex=${resourcesDir}/context_indices;
echo "Creating directory $conIndex";
mkdir $conIndex;

tempDir=${resourcesDir}/makeResourcesAndIndicesTemp;
echo "Copy scripts and files for creation of resources and indices to temporary directory $tempDir";
mkdir $tempDir;
cp *.* $tempDir;
#cp non_descriptives $tempDir;
echo "Go to temporary directory to run scripts";
cd $tempDir;

echo "Run script _makeGeneDictionary.sh to create dictionaries|biothesauri";
echo "Command: bash _makeGeneDictionary.sh $DOWNLOAD_DIR $res $ORGANISM_LIST $GENE_INFO_ORG_FILTERED $GENE_META_CACHING_DIR $GENE2XML $GENE_IDS_TO_INCLUDE $GENE_ID_LIST"
bash _makeGeneDictionary.sh $DOWNLOAD_DIR $res $ORGANISM_LIST $GENE_INFO_ORG_FILTERED $GENE_META_CACHING_DIR $GENE2XML $GENE_IDS_TO_INCLUDE $GENE_ID_LIST;
if [[ 0 -ne "$?" ]]; then
	echo "Error occured, exiting".
	exit 1
fi

echo "Run script makeSemanticContext.sh to create semantic context resources";
echo "Command: bash makeSemanticContext.sh $DOWNLOAD_DIR $res $ORGANISM_LIST $GENE_INFO_ORG_FILTERED $GENE_META_CACHING_DIR $GENE2XML"
bash _makeSemanticContext.sh $DOWNLOAD_DIR $res $ORGANISM_LIST $GENE_INFO_ORG_FILTERED $GENE_META_CACHING_DIR $GENE2XML;
if [[ 0 -ne "$?" ]]; then
	echo "Error occured, exiting".
	exit 1
fi

export CLASSPATH=.:jules-gene-mapper-ae.jar;
echo "Run class SynonymIndexGenerator for creation of gene-synonym-index";
echo "java -cp gene-name-mapping-resource-creation.jar:. de.julielab.jules.ae.genemapping.resources.NameCentricSynonymIndexGenerator $res $GENE_INFO_ORG_FILTERED $synIndex"
java -cp gene-name-mapping-resource-creation.jar:. de.julielab.jules.ae.genemapping.resources.NameCentricSynonymIndexGenerator $res $GENE_INFO_ORG_FILTERED $synIndex;
if [[ 0 -ne "$?" ]]; then
	echo "Error occured, exiting".
	exit 1
fi
#echo "Run class ContextIndexGenerator for creation of gene-context-index";
#echo "java -cp gene-name-mapping-resource-creation.jar:. de.julielab.jules.ae.genemapping.resources.ContextIndexGenerator $res $conIndex"
#java -cp gene-name-mapping-resource-creation.jar:. de.julielab.jules.ae.genemapping.resources.ContextIndexGenerator $res $conIndex;
#if [[ 0 -ne "$?" ]]; then
#	echo "Error occured, exiting".
#	exit 1
#fi

echo "Run class SynonymDisambiguationIndexGenerator for creation of gene information";
echo "java -cp gene-name-mapping-resource-creation.jar:. de.julielab.jules.ae.genemapping.resources.SynonymDisambiguationIndexGenerator $res $conIndex"
java -cp gene-name-mapping-resource-creation.jar:. de.julielab.jules.ae.genemapping.resources.SynonymDisambiguationIndexGenerator $res $conIndex;
if [[ 0 -ne "$?" ]]; then
	echo "Error occured, exiting".
	exit 1
fi

echo "Run class SpellCheckerIndexGenerator for creation of spelling indixes";
java -cp gene-name-mapping-resource-creation.jar:. de.julielab.jules.ae.genemapping.resources.SpellCheckerIndexGenerator $res $synIndex;
if [[ 0 -ne "$?" ]]; then
	echo "Error occured, exiting".
	exit 1
fi

echo "Remove jar";
rm gene-name-mapping-resource-creation.jar;
#echo "Remove temporary directory";
#rm -r $tempDir;

echo "Done creating resources and indices";
