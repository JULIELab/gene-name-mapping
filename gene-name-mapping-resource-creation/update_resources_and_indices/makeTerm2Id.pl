#!/usr/bin/perl -w

while($line = <>) {

  chomp $line;
  @split = split(/\t/, $line);

  $id = $split[0];
  
  for($i=1; $i<scalar(@split); $i++) {

    $syn = $split[$i];
    if($syn ne "-") {
      print $split[$i] . "\t" . $id . "\n";
    }
  }
  

}
