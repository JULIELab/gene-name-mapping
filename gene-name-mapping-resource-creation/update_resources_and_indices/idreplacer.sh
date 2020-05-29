#!/bin/bash
# This is a small Java programm to quickly replace IDs in a file according to a separate mapping file.
# The parameters are as follows:
# 1: some tab-separated file to replace IDs in
# 2: the ID column index of the file in 1.
# 3: a two-column tab-separated mapping file
# 4: the source ID column of the mapping file, the other column will then be the target IDs
# 5: OPTIONAL an output file; if not given, output will go to STDOUT
 
java -cp gene-name-mapping-resource-creation.jar de.julielab.jules.ae.genemapping.resources.IDReplacer $1 $2 $3 $4 $5