#!/usr/bin/env Rscript

####
# Installs all Bioconductor annotation packages necessary for EC number recognition task.
# Multiple entries in a column are separated by semicolon.
#
# The first parameter to the script is the path to a directory containing an auxiliary file.
# - BioC-org-databases: Lists all useful annotation packages
# The second parameter should point to a directory where the R libraries can be stored that
# are necessary to build the final resources.
#
# Run: Rscript BioC-setup.R <path-to-dir> <library download directory>
####

###


args <- commandArgs(trailingOnly = TRUE)
auxfiledir <- args[1]
downloaddir <- args[2]
msg <- paste("Auxiliary file is searched for in ", auxfiledir)
msg
msg <- paste("Library download directory is ", downloaddir)
msg
stopifnot(file.exists(auxfiledir), file.info(auxfiledir)$isdir)
stopifnot(file.exists(downloaddir), file.info(downloaddir)$isdir)
.libPaths(downloaddir) # Libraries will get installed in this directory

# Setup part
source("http://bioconductor.org/biocLite.R")
BioCorgDbs <- file.path(auxfiledir, "BioC-org-databases")
stopifnot(file.exists(BioCorgDbs))
orgDbs <- read.table(BioCorgDbs, sep = "\t")
orgDbs <- as.vector(orgDbs[,1])

biocLite(orgDbs) # Install all databases
