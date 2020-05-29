#!/usr/bin/perl -w

while ($line = <>) {

chomp $line;

# Always print out the official symbols
if($line =~ m/^.*-1$/) {
    print $line . "\n";
    next;
}

if($line =~ m/^(receptor|aav2|abalone|acmnpv|acremonium chrysogenum|aeromonas proteolytica|african clawed frog|alfalfa|american alligator|amoeba|amphioxus|aphid|apricot|arav|armv|armyworm|arv|asgv|aspergillus nidulans|atlantic herring|ayu|baboon|bacillus macerans|bacillus polymyxa|balsa|barber pole worm|barley|baymv|bbv|bcmv|bdv|beechdrops|beet|bev|bfdv|bfv|bitter gourd|biv|black mamba|black spruce|blrv|blue whale|blv|bmdv|bmnpv|bmv|bov|bpv|bpyv|brav|broad-fingered crayfish|brown sea anemone|brs|btmv|bvdv|bydv|byv|cabbage|cacao|california bay|california sea hare|camv|carob|ccmv|cdv|cerv|cgmmv|cherry|chimpanzee|chinese hamster|chlamydophila pneumoniae|civ|clgv|cmv|cnpv|cnv|cockroach|coffee|common tobacco|cone|cotton|cowdria ruminantium|cowpea|cpgv|cpmv|cpv|crayfish|crpv|csfv|csmv|ctv|cucumber|cvb|cymrsv|cymv|dodder|earthworm|eav|ecov|ehv-1|eiav|eland|electric ray|elv|emu|ermine|ev-9|evening primrose|fcv|ffv|fhv|filobasidiella neoformans|firefly|fission yeast|fiv|fpv|frog|fruit fly|garden pea|garden snapdragon|gflv|grape|gray seal|haloferax volcanii|hamster|hav|hbv|hev|hippopotamus|hiv-1|hiv-2|holly|horse|htlv-1|human|ibv|indri|ipnv|japanese quail|jdv|jellyfish|kidney bean|kimsv|kob|kymv|lbv|ldmnpv|ldv|lmv|lsdv|lsv|lyme disease spirochete|maize|mamushi|mayv|mdv|mediterranean mussel|mesorhizobium loti|mev|mhv|midge|mmv|mnpv|mojave rattlesnake|monocled cobra|mosquito|moth|mpv|mung bean|mushroom|mvv|myxv|ndv|nectria haematococca|niger|nmv|north american opossum|nyala|nyv|oil palm|onion|orchid|pacific herring|pacu|papaya|pbcv-1|pdv|peach|pear|pedv|pemv|perch|petunia|phv|pichi|planarian|plrv|pmv|pokeweed|potato|powv|prrsv|pseudomonas solanacearum|pumpkin|rabbit|radish|rbsdv|rdv|red ant|rgdv|rice|rmv|roach|rook|rprsv|rrv|rubv|russells?.?s? viper|rvfv|rye|sable|salmon|scallop|sdv|sea anemone|sea lamprey|sea star|sea urchin|sepi|sfv|sheep|shrew|sinv|skipjack tuna|smv|snapdragon|sorghum|soybean|spinach|sponge|stargazer|strawberry|sturgeon|sv40|sv41|sv5|synv|t2j|tav|tbsv|tcov|tcv|tev|tgev|thermosynechococcus elongatus|tho|tick|tiger|tiv|tmgmv|tmv|tnv|tobacco|tomato|tomv|tswv|tumv|tung|udo|uuk|vacv|vanilla|vhsv|viper|vsiv|wdv|weev|wheat|wiv|wssv|yam|yeast|zucchini|mouse|mice|Mice|Mouse|human|Human|zebrafish|Zebrafish|rat|Rat|cell|dog|cytokine|polymerase|polymerase protein|chemokine|mitochondrial|tyrosine kinase|high affinity|dendritic cell|growth hormone|chemokine receptor|protein kinase|repressor protein|regulatory genes|Regulatory genes|transcriptional regulator|Mitochondrial|Tyrosine kinase|High affinity|dendritic cell|Growth hormone|Chemokine receptor|Protein kinase|Repressor protein)\t.*/gi) {

  next;
}


if ($line =~ m/^.*?(probable|Probable|predicted|Predicted|putative|Putative|hypothetical|Hypothetical|unknown|Unknown|novel|Novel|uncharacterized|Uncharacterized).*\t.*$/g) {
  next;
}

if($line =~ m/^([0-9]*\W*[0-9]*)+\t.*$/g) {
  next;
}

if($line =~ m/^.*[Rr][Ii][Kk].*\t.*$/g) {
  next;
}

if($line =~ m/^[A-Za-z]\t.*$/g) {
  next;
}

if($line =~ m/^[0-9]+.?[0-9]+ ?k ?da\t.*$/gi) {
  #print $line . "\n";
  next;
}



print $line . "\n";

}
