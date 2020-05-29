#!/usr/bin/env Rscript

####
# Generates text files containing information about EC numbers out of Bioconductor annotation packages.
# These files consist of three columns separated by tabs with
# 1. UniProt accession numbers OR EntrezGene ID
# 2. EC number
# 3. Common symbols
#
# and will be written to the working directory.
# Multiple entries in a column are separated by semicolon.
#
# The first parameter to the script is the path to a directory containing two auxiliary files.
# a) BioC-org-databases: Lists all annotation packages for the task
# b) sec_ac.txt: index of secondary accession numbers
# The second parameter should point to the library download directory already specified when running
# the BioC-setup.R script.
# The third parameter should point to the final resources directory. The ID mappings will be stored there.
#
# Run: Rscript BioC-OrgDbs.R <path-to-dir> <library download dir> <final resources dir>
####

###

args <- commandArgs(trailingOnly = TRUE)
auxfiledir <- args[1]
downloaddir <- args[2]
resdir <- args[3]
msg <- paste("Auxiliary file is searched for in ", auxfiledir)
msg
msg <- paste("Temporary library download directory is ", downloaddir)
msg
msg <- paste("Final resource storage directory is ", resdir)
msg
stopifnot(file.exists(auxfiledir), file.info(auxfiledir)$isdir)
stopifnot(file.exists(downloaddir), file.info(downloaddir)$isdir)
.libPaths(downloaddir) # Libraries will get installed in this directory

# Setup part
source("http://bioconductor.org/biocLite.R")
biocLite() # Updates packages

options(repos = "https://cran.uni-muenster.de")
if (!("fastmatch" %in% rownames(installed.packages()))) {
   install.packages("fastmatch")
}
library(fastmatch)

####
#   secAc: Path to the index of secondary accession numbers
####
loadUniprotMapping<-function(secAc) {
   sec <- readLines(secAc)
   start <- grep("^______", sec)
   # Beginning of table is not unambiguous
   stopifnot(length(start) == 1)
   sec <- sec[(start+1):length(sec)]
   sec <- strsplit(sec, "\\s+")
   # Every lineshould consists of exactly two columns, separated by whitespace
   stopifnot(all(sapply(sec, length) == 2))
   primary <- sapply(sec, "[", 2)
}

###
# Start of processing

#setwd(path)
BioCorgDbs <- file.path(auxfiledir, "BioC-org-databases")
stopifnot(file.exists(BioCorgDbs))
orgDbs <- read.table(BioCorgDbs, sep = "\t")
orgDbs <- as.vector(orgDbs[,1])

orgPrefix <- substr(orgDbs, 1, nchar(orgDbs)-3)
orgEnzyme <- paste0(orgPrefix, "ENZYME")
# Map between Common Gene Symbol Identifiers and Entrez Gene
orgAlias <- paste0(orgPrefix, "ALIAS2EG")
orgUniprot <- paste0(orgPrefix, "UNIPROT")
orgOrganism <- paste0(orgPrefix, "ORGANISM")
orgPackage <- paste("package", orgDbs, sep=":")

secAc <- file.path(auxfiledir, "sec_ac.txt")
stopifnot(file.exists(secAc))
primary <- loadUniprotMapping(secAc)

bar <- txtProgressBar(min = 0, max = length(orgDbs), style = 3)
for (i in seq_along(orgDbs)) {
   library(orgDbs[i], character.only = TRUE)

   x <- eval(parse(text = orgEnzyme[i]))
   mapped_genes <- mappedkeys(x)
   egEC <- as.list(x[mapped_genes])

   entries <- sapply(egEC, length)
   egEC <- cbind(rep(names(egEC), entries), unlist(egEC))

   # Combine all EC numbers into one string, separated by ";"
   ecNumbers <- lapply(split(egEC[,2], egEC[,1]), paste0, collapse = ";")
   index <- unique(egEC[,1])
   egEC <- cbind(index, unlist(ecNumbers[index]))      

   # Concatenate all common symbols per Gene ID
   geneSymbols <- as.list(eval(parse(text = orgAlias[i])))
   entries <- sapply(geneSymbols, length)
   egSymbols <- cbind(rep(names(geneSymbols), entries), unlist(geneSymbols))
   egSymbols <- lapply(split(egSymbols[,1], egSymbols[,2]), paste, sep = "", collapse = ";")
   egSymbols <- unlist(egSymbols[mapped_genes]) # all aliases per symbol
   entrezTable <- cbind(egEC, egSymbols)

   fileOrganism <- gsub(" ", "_", eval(parse(text = orgOrganism[i])))
   write.table(entrezTable, file = sprintf("%s/EntrezGene_%s.txt", resdir, fileOrganism), sep = "\t", quote = F, col.names=F, row.names=F)

   # Uniprot IDs don't exist for all databases (e.g. EcK12)
   if (orgUniprot[i] %in% ls(orgPackage[i])) {
      # Uniprot IDs
      uniIds <- eval(parse(text = orgUniprot[i]))
      uniIds <- as.list(uniIds[mapped_genes])

      normalizedKeys <- lapply(uniIds, function(x){x[x %fin% primary]})
      nokeys <- sapply(normalizedKeys, length)
      nokeys <- names(nokeys)[nokeys == 0]
      normalizedKeys[nokeys] <- uniIds[nokeys]
      noNaKeys <- sapply(normalizedKeys, function(x){length(x) == 1 && is.na(x)})
      noNaKeys <- names(noNaKeys)[!noNaKeys]
      normalizedKeys <- sapply(normalizedKeys, paste0, collapse = ";")

      uniProtTable <- cbind(normalizedKeys, egEC[,2], egSymbols)
      # A few Accession numbers might be NA
      uniProtTable <- uniProtTable[noNaKeys,]
      write.table(uniProtTable, file = sprintf("%s/UniProt_%s.txt", resdir, fileOrganism), sep = "\t", quote = F, col.names=F, row.names=F)
   }

   setTxtProgressBar(bar, i)
   detach(orgPackage[i], character.only = TRUE)
}
close(bar)

