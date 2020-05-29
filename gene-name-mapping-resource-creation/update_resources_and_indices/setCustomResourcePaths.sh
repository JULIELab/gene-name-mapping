#!/bin/bash
# This script is typically sourced from within metaScript.sh.
# It justs the environment variables used by the
# _makeGeneDictionary.sh and _makeSemanticContext.sh scripts
# to find the required input sources like NCBI Gene databases,
# UniProt, ID mappings etc.
# All these resources are downloaded or created by the 
# external-resources-download/downloadExternalResources.sh
# script.
# Using this script, you may place the downloaded resources
# into any folder in any structure you like and let this file
# point the variables to the correct location.

# Resources used by _makeGeneDictionary.sh and _makeSemanticContext.sh

export GENE_ASN1=

# Resources used by _makeGeneDictionary.sh

export GENE_INFO=
export IDMAPPING=
export UNIPROT_DAT=
export UNIPROT_XML=
export BIO_THESAURUS=
export IPROCLASS=
export BIOC=

# Resources used by _makeSemanticContext.sh

export GENERIF_BASIC=
export GENE2GO=
export UNIPROT_GOA=
export GO=
export UNIPROT_XML=
export INTERACTIONS=
export FULL_GENE_INFO=$GENE_INFO

