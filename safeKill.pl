#!/usr/bin/perl
use strict;
use warnings;

my $pid = shift;
my $regex = shift or die "usage: safeKill.pl pid regex\n";

my $file = "/proc/$pid/cmdline";
open(my $fh,"<",$file) or die "$file $!\n";
my $cmdline = <$fh>;
close($fh) or die "$file $!\n";
$cmdline =~ s/\0/ /g;
$cmdline =~ /$regex/ or die "cmdline not match: $cmdline\n";

kill "INT", $pid;
kill "TERM", $pid;
