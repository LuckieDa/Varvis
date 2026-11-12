use strict;
use warnings;
use FindBin;
use lib $FindBin::Bin;
use VarvisTranscriptModelExport;
use Digest::SHA qw(sha256_hex);
use Getopt::Long qw(GetOptions);
use JSON::PP;
use Fcntl qw(O_WRONLY O_CREAT O_EXCL);

# Reads only the explicitly named small metadata file and loaded API source files.
# This does not run VEP or read any transcript cache shard.
my ($info, $info_sha, $request_id, $output, @transcripts);
GetOptions('cache-info=s' => \$info, 'cache-info-sha256=s' => \$info_sha,
  'request-id=s' => \$request_id, 'transcript=s@' => \@transcripts, 'output=s' => \$output)
  or die "INVALID_ARGUMENTS\n";
die "REQUIRED_ARGUMENTS_MISSING\n" unless !@ARGV && defined($info) && defined($info_sha)
  && defined($request_id) && $request_id =~ /^[A-Za-z0-9_.-]+$/ && defined($output)
  && @transcripts >= 1 && @transcripts <= 32;
my %seen;
for (@transcripts) { die "EXACT_UNIQUE_ENST_REQUIRED\n" unless /^ENST[0-9]+\.[1-9][0-9]*$/ && !$seen{$_}++; }
my $info_bytes = VarvisTranscriptModelExport::_bytes($info, 100_000);
die "CACHE_INFO_SHA_MISMATCH\n" unless sha256_hex($info_bytes) eq $info_sha;
my $json = JSON::PP->new->canonical->utf8;
my $request = {schemaVersion => 'vep116-transcript-export-request-v1', requestId => $request_id,
  assembly => 'GRCh38', species => 'homo_sapiens', cacheVersion => '116_GRCh38', requestedTranscripts => \@transcripts,
  cacheInfo => {path => $info, sha256 => $info_sha}, apiSourceSha256 => VarvisTranscriptModelExport::api_source_sha256()};
my $bytes = $json->encode($request)."\n";
sysopen(my $fh, $output, O_WRONLY | O_CREAT | O_EXCL) or die "OUTPUT_EXISTS_OR_UNWRITABLE\n";
binmode($fh, ':raw'); print $fh $bytes or die "WRITE_FAILED\n"; close $fh or die "CLOSE_FAILED\n";
print $json->encode({requestSha256 => sha256_hex($bytes),
  exporterSha256 => sha256_hex(VarvisTranscriptModelExport::_bytes($INC{'VarvisTranscriptModelExport.pm'}, 1_000_000)),
  apiSourceSha256 => $request->{apiSourceSha256}})."\n";
