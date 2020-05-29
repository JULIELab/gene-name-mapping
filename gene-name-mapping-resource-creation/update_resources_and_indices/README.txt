To build the JCoRe Gene Mapper resources, the following requirements
must be met:
* A *nix operating system (Mac is fine) or using Cygwin on Windows
* perl must be installed
* The R statistics package in version >= 3.4 must be installed

The metaScript.sh script is the central entry point for JCoRe Gene Mapper
resource creation. At the top of the metaScript you will find a few settings
you might want to change:
* The directory where to store the final resources.
* The organism filter list applied to resource generation. Only genes
  associated with the given organisms will be included.
  
To build the Gene Mapper resources:
1. Download the required external resources if they are not already present
     (will take a few hours):
   a) enter, in a terminal, the external-resources-download directory
   b) run the downloadExternalResources.sh script
2. Run metaScript.sh
