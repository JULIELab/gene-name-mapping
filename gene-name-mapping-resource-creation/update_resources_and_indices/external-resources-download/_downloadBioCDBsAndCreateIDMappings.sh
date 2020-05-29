#!/bin/bash
# Downloads Enzyme databases from BioConductor via the R API.
# Required R >= 3.4

if [ -z "$DOWNLOAD_DIR" ]; then
	DOWNLOAD_DIR=download
	echo "Using default download directory: $DOWNLOAD_DIR";
else
	echo "Using given download directory: $DOWNLOAD_DIR";
fi

if [ -z "$BIOC_DIR" ]; then 
	BIOC_DIR=$DOWNLOAD_DIR/bioc_libraries
fi

if [ ! -d "$BIOC_DIR" ]; then
	echo "Creating download directory $BIOC_DIR";
	mkdir $BIOC_DIR;
	if [ ! 0 -eq $? ]; then
		echo "Could not create bioconductor directory, aborting.";
		exit 1;
	fi
fi

if [ "$1" == "nd" ]; then
	echo "User wishes to supress BioC database download, skipping.";
else
	echo "Downloading BioC databases";
	Rscript bioc/BioC-setup.R bioc $BIOC_DIR;
fi

echo "Extracting mapping files from BioC databases";
Rscript bioc/BioC-OrgDbs.R bioc $BIOC_DIR $DOWNLOAD_DIR;
