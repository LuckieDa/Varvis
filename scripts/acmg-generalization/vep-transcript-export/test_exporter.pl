use strict;
use warnings;
use FindBin;
use lib $FindBin::Bin;
use VarvisTranscriptModelExport;
use Bio::EnsEMBL::Attribute;
use Digest::SHA qw(sha256_hex);
use File::Temp qw(tempdir);
use JSON::PP;
use Test::More;
use Storable qw(freeze);

my $JSON = JSON::PP->new->canonical->utf8;
my $dir = tempdir(CLEANUP => 1);
my $target = 'ENST00000000001.2';
my $counter = 0;
sub bytes_write {
  my ($path, $bytes) = @_;
  open my $fh, '>:raw', $path or die $!;
  print $fh $bytes; close $fh or die $!;
}
bytes_write("$dir/info.txt", "species\thomo_sapiens\nassembly\tGRCh38\n");
sub transcript {
  my ($strand, $slice_strand) = @_;
  $strand ||= 1; $slice_strand ||= 1;
  my $cs = Bio::EnsEMBL::CoordSystem->new(-NAME => 'chromosome', -VERSION => 'GRCh38', -RANK => 1);
  my $slice = Bio::EnsEMBL::Slice->new(-COORD_SYSTEM => $cs, -START => 51, -END => 1050,
    -STRAND => $slice_strand, -SEQ_REGION_NAME => '10', -SEQ_REGION_LENGTH => 2000);
  my @bounds = $strand == 1 ? ([101,103], [201,206], [301,306]) : ([301,303], [201,206], [101,106]);
  my @exons;
  for my $i (0..2) {
    my ($lo, $hi) = @{$bounds[$i]};
    my ($start, $end) = $slice_strand == 1 ? ($lo - 50, $hi - 50) : (1051 - $hi, 1051 - $lo);
    push @exons, Bio::EnsEMBL::Exon->new(-START => $start, -END => $end, -STRAND => $strand * $slice_strand,
      -SLICE => $slice, -STABLE_ID => 'ENSE0000000000'.($i+1));
  }
  my $tr = Bio::EnsEMBL::Transcript->new(-EXONS => \@exons, -STABLE_ID => 'ENST00000000001',
    -VERSION => 2, -BIOTYPE => 'protein_coding');
  $tr->translation(Bio::EnsEMBL::Translation->new(-START_EXON => $exons[1], -END_EXON => $exons[2],
    -SEQ_START => 1, -SEQ_END => 3, -STABLE_ID => 'ENSP00000000001', -VERSION => 3));
  $tr->{attributes} = [];
  $tr->{cdna_coding_start} = 4; $tr->{cdna_coding_end} = 12;
  delete $tr->{edits_enabled};
  $tr->{_variation_effect_feature_cache} = {translateable_seq => 'ATGAAATAA', peptide => 'MK', codon_table => 1, seq_edits => []};
  # Restored adaptors are poisoned: extraction must never ask them for data.
  $slice->{circular} = 0;
  $slice->{adaptor} = bless {}, 'NoDataAccess';
  $tr->{adaptor} = $tr->{translation}->{adaptor} = $slice->{adaptor};
  return $tr;
}
{
  package NoDataAccess;
  our $AUTOLOAD;
  sub AUTOLOAD { require Carp; Carp::confess("UNEXPECTED_ADAPTOR_CALL: $AUTOLOAD\n"); }
  sub DESTROY {}
  package SyntheticTVA;
  sub transcript { return $_[0]->{transcript}; }
}
sub extract { return VarvisTranscriptModelExport::extract_transcript($_[0], $target); }
sub rejects {
  my ($tr, $reason, $name) = @_;
  eval { extract($tr); 1 };
  like($@, qr/^\Q$reason\E\n/, $name);
}

# Guard all common sequence retrieval entry points, including translation->seq.
{
  no warnings 'redefine';
  local *Bio::EnsEMBL::Transcript::seq = sub {die 'UNEXPECTED_SEQUENCE_FETCH'};
  local *Bio::EnsEMBL::Transcript::translateable_seq = sub {die 'UNEXPECTED_SEQUENCE_FETCH'};
  local *Bio::EnsEMBL::Transcript::translate = sub {die 'UNEXPECTED_SEQUENCE_FETCH'};
  local *Bio::EnsEMBL::Translation::seq = sub {die 'UNEXPECTED_SEQUENCE_FETCH'};
  local *Bio::EnsEMBL::Slice::seq = sub {die 'UNEXPECTED_SEQUENCE_FETCH'};
  for my $strand (1, -1) { for my $slice_strand (1, -1) {
    my $body = extract(transcript($strand, $slice_strand));
    is_deeply([@{$body->{codingBoundaries}}{qw(cdnaStart cdnaEnd cdsLengthIncludingStop proteinLength)}],
      [4,12,9,2], "full UTR exons retained, strand $strand / slice $slice_strand");
    is_deeply([@{$body->{lookup}->{Translation}}{qw(start end)}], $strand == 1 ? [201,303] : [104,206],
      'absolute CDS boundaries include stop, not slice-relative or exon-local offsets');
    is($body->{lookup}->{length}, 15, 'full transcript length includes both UTRs');
    is($body->{objectSequenceChecks}->{cdsSha256}, sha256_hex('ATGAAATAA'), 'cached CDS hash with no retrieval');
  } }
  my $tr = transcript();
  $tr->{biotype} = Bio::EnsEMBL::Biotype->new(-NAME => 'protein_coding', -OBJECT_TYPE => 'transcript');
  $tr->{biotype}->{adaptor} = $tr->{adaptor};
  {
    no warnings 'redefine';
    local *Bio::EnsEMBL::Transcript::biotype = sub {die 'UNEXPECTED_BIOTYPE_GETTER'};
    local *Bio::EnsEMBL::Transcript::get_Biotype = sub {die 'UNEXPECTED_BIOTYPE_GETTER'};
    local *Bio::EnsEMBL::Biotype::name = sub {die 'UNEXPECTED_BIOTYPE_GETTER'};
    local $Storable::canonical = 1;
    my $before = freeze($tr);
    is(extract($tr)->{lookup}->{biotype}, 'protein_coding', 'pre-resolved cached Biotype accepted without accessors');
    is(freeze($tr), $before, 'cached Biotype and transcript remain unchanged');
  }
  for my $bad (undef, 'lncRNA', {}, bless({name => 'protein_coding', object_type => 'transcript'}, 'UntrustedBiotype'),
      Bio::EnsEMBL::Biotype->new(-NAME => 'lncRNA', -OBJECT_TYPE => 'transcript'),
      Bio::EnsEMBL::Biotype->new(-NAME => 'protein_coding', -OBJECT_TYPE => 'gene'),
      Bio::EnsEMBL::Biotype->new(-OBJECT_TYPE => 'transcript')) {
    $tr = transcript(); $tr->{biotype} = $bad;
    rejects($tr, 'VEP_MODEL_PROTEIN_CODING_REQUIRED', 'missing, wrong or untyped cached biotype never defaults');
  }
  $tr = transcript(); delete $tr->{version}; rejects($tr, 'VEP_MODEL_TRANSCRIPT_VERSION_MISMATCH', 'missing version is not defaulted');
  $tr = transcript(); $tr->{version} = 3; rejects($tr, 'VEP_MODEL_TRANSCRIPT_VERSION_MISMATCH', 'different version refused');
  $tr = transcript(); delete $tr->{translation}->{version}; rejects($tr, 'VEP_MODEL_PROTEIN_VERSION_MISSING', 'protein version required');
  $tr = transcript(); $tr->{translation}->stable_id('ENSPbad');
  rejects($tr, 'VEP_MODEL_PROTEIN_VERSION_MISSING', 'failed terminal protein regex is a scalar false, not an empty argument list');
  $tr = transcript(); delete $tr->{_trans_exon_array}; rejects($tr, 'VEP_MODEL_CACHED_EXONS_OR_TRANSLATION_MISSING', 'no lazy exon fetch');
  $tr = transcript(); delete $tr->{translation}; rejects($tr, 'VEP_MODEL_CACHED_EXONS_OR_TRANSLATION_MISSING', 'no lazy translation fetch');
  $tr = transcript(); delete $tr->{attributes}; rejects($tr, 'VEP_MODEL_CACHED_ATTRIBUTES_MISSING', 'missing attributes do not mean no edits');
  for my $code (qw(_rna_edit _transl_start _transl_end cds_start_NF cds_end_NF)) {
    $tr = transcript(); $tr->{attributes} = [Bio::EnsEMBL::Attribute->new(-CODE => $code, -VALUE => '1')];
    rejects($tr, 'VEP_MODEL_EDITED_OR_INCOMPLETE_CDS_UNSUPPORTED', "reject $code");
  }
  $tr = transcript(); delete $tr->{_variation_effect_feature_cache}->{seq_edits};
  rejects($tr, 'VEP_MODEL_CACHED_TRANSLATION_EDITS_OR_CODE_UNSUPPORTED', 'missing translation edit cache refused');
  $tr = transcript(); $tr->{_variation_effect_feature_cache}->{codon_table} = 2;
  rejects($tr, 'VEP_MODEL_CACHED_TRANSLATION_EDITS_OR_CODE_UNSUPPORTED', 'unsupported codon table refused');
  $tr = transcript(); delete $tr->{_variation_effect_feature_cache}->{translateable_seq};
  rejects($tr, 'VEP_MODEL_CACHED_CDS_OR_PEPTIDE_INCOMPLETE', 'missing cached CDS never fetched');
  $tr = transcript(); $tr->{_variation_effect_feature_cache}->{peptide} = 'ML';
  rejects($tr, 'VEP_MODEL_CACHED_CDS_PEPTIDE_MISMATCH', 'cached peptide contradiction refused');
  $tr = transcript(); delete $tr->slice->{circular};
  rejects($tr, 'VEP_MODEL_NATIVE_SLICE_REQUIRED', 'missing circular cache never consults adaptor');
  $tr = transcript(); $tr->slice->{circular} = undef;
  rejects($tr, 'VEP_MODEL_NATIVE_SLICE_REQUIRED', 'undefined circular cache is missing');
  $tr = transcript(); delete $tr->slice->{adaptor}; $tr->slice->{circular} = 1;
  rejects($tr, 'VEP_MODEL_NATIVE_SLICE_REQUIRED', 'explicit circular state cannot disappear with adaptor removal');
  $tr = transcript(); $tr->{_trans_exon_array}->[2]->stable_id($tr->{_trans_exon_array}->[1]->stable_id);
  rejects($tr, 'VEP_MODEL_EXON_IDENTITY_INVALID', 'duplicate exon identity refused');
  $tr = transcript(); @{$tr->{_trans_exon_array}} = reverse @{$tr->{_trans_exon_array}};
  rejects($tr, 'VEP_MODEL_EXON_ORDER_OR_OVERLAP', 'wrong transcript exon order refused');
  $tr = transcript(); $tr->{translation}->{start_exon} = transcript()->{_trans_exon_array}->[1];
  rejects($tr, 'VEP_MODEL_CDS_BOUNDARIES_INCOMPLETE', 'same-id foreign boundary object refused');
  $tr = transcript(); delete $tr->{cdna_coding_start};
  rejects($tr, 'VEP_MODEL_CACHED_CDNA_BOUNDARY_MISMATCH', 'missing cDNA coordinate cache refused');
  $tr = transcript(); $tr->{cdna_coding_start} = 5;
  rejects($tr, 'VEP_MODEL_CACHED_CDNA_BOUNDARY_MISMATCH', 'contradictory cDNA coordinate cache refused');
}

sub request {
  return {schemaVersion => 'vep116-transcript-export-request-v1', requestId => 'synthetic-v1', assembly => 'GRCh38',
    species => 'homo_sapiens', cacheVersion => '116_GRCh38', requestedTranscripts => [$target],
    cacheInfo => {path => "$dir/info.txt", sha256 => sha256_hex("species\thomo_sapiens\nassembly\tGRCh38\n")},
    apiSourceSha256 => VarvisTranscriptModelExport::api_source_sha256()};
}
sub plugin {
  my ($request, $changes, $bad_sha, $output) = @_;
  my $prefix = "$dir/".++$counter;
  my $bytes = $JSON->encode($request)."\n";
  bytes_write("$prefix.request.json", $bytes);
  my $config = {offline => 1, cache => 1, assembly => 'GRCh38', species => 'homo_sapiens',
    cache_version => 116, full_cache_dir => $dir, %{$changes || {}}};
  my $path = $output || "$prefix.export.jsonl";
  return (VarvisTranscriptModelExport->new($config,
    "request=$prefix.request.json", 'request_sha256='.($bad_sha || sha256_hex($bytes)), "output=$path"), $path, $bytes);
}
sub records {
  open my $fh, '<:raw', $_[0] or die $!;
  my @lines = map {$JSON->decode($_)} <$fh>; close $fh;
  return \@lines;
}
sub run_tr { $_[0]->run(bless {transcript => $_[1]}, 'SyntheticTVA'); }
my ($plugin, $path, $req_bytes) = plugin(request());
my $ignored = transcript(); $ignored->stable_id('ENST00000000099');
is_deeply(run_tr($plugin, $ignored), {}, 'no annotation fields emitted');
run_tr($plugin, transcript()); run_tr($plugin, transcript()); $plugin->finish; $plugin->finish;
my $records = records($path);
is_deeply([map {$_->{recordType}} @$records], [qw(HEADER TRANSCRIPT FOOTER)], 'only requested object exported once with normal finish');
is($records->[-1]->{transcripts}->[0]->{observations}, 2, 'identical repeats counted without ambiguity');
is($records->[-1]->{transcripts}->[0]->{status}, 'EXPORTED_UNIQUE', 'matching object identity unique');
ok(!$records->[0]->{clinicalAcceptance} && !$records->[0]->{defaultEnabled}, 'source candidate never clinically enabled');
if ($ENV{VARVIS_EXPORT_TEST_ARTIFACT_DIR}) {
  bytes_write($ENV{VARVIS_EXPORT_TEST_ARTIFACT_DIR}.'/synthetic-request.json', $req_bytes);
  open my $fh, '<:raw', $path or die $!; local $/; my $bytes = <$fh>; close $fh;
  bytes_write($ENV{VARVIS_EXPORT_TEST_ARTIFACT_DIR}.'/synthetic-export.jsonl', $bytes);
}
($plugin, $path) = plugin(request()); run_tr($plugin, transcript());
my $different = transcript(-1); run_tr($plugin, $different); $plugin->finish;
$records = records($path);
is($records->[2]->{recordType}, 'CONFLICT', 'two source identities explicitly conflict');
is($records->[-1]->{transcripts}->[0]->{status}, 'CONFLICT', 'conflict cannot complete as unique');
($plugin, $path) = plugin(request()); $plugin->finish;
is(records($path)->[-1]->{transcripts}->[0]->{status}, 'NOT_OBSERVED', 'request not overlapping input is not manufactured');
($plugin, $path) = plugin(request()); my $missing = transcript(); delete $missing->{version}; run_tr($plugin, $missing); $plugin->finish;
is(records($path)->[1]->{body}->{reasonCode}, 'VEP_MODEL_TRANSCRIPT_VERSION_MISMATCH', 'per-transcript missing-source failure structured');
for my $case (
  [{offline => 0}, 'VEP_MODEL_OFFLINE_NATIVE_116_REQUIRED'], [{fork => 2}, 'VEP_MODEL_OFFLINE_NATIVE_116_REQUIRED'],
  [{db_version => 115}, 'VEP_MODEL_CACHE_VERSION_MISMATCH'], [{full_cache_dir => "$dir/absent"}, 'VEP_MODEL_EFFECTIVE_CACHE_INFO_MISMATCH']) {
  eval { plugin(request(), $case->[0]); 1 }; like($@, qr/^\Q$case->[1]\E\n/, 'unsafe or mismatched config refused');
}
eval { plugin(request(), {}, '0'x64); 1 }; like($@, qr/^VEP_MODEL_REQUEST_SHA_MISMATCH/, 'pinned request hash mandatory');
my $bad = request(); push @{$bad->{requestedTranscripts}}, $target;
eval { plugin($bad); 1 }; like($@, qr/^VEP_MODEL_EXACT_TRANSCRIPT_REQUEST_REQUIRED/, 'duplicate target refused');
$bad = request(); $bad->{apiSourceSha256}->{'Bio/EnsEMBL/Transcript.pm'} = '0'x64;
eval { plugin($bad); 1 }; like($@, qr/^VEP_MODEL_API_SOURCE_MISMATCH/, 'actual source API bytes pinned');
$bad = request(); $bad->{cacheInfo}->{sha256} = 'invalid';
eval { plugin($bad); 1 }; like($@, qr/^VEP_MODEL_CACHE_INFO_BINDING_INVALID/, 'failed terminal digest regex preserves its rejection reason');
eval { plugin(request(), {}, undef, "$dir/info.txt"); 1 };
like($@, qr/^VEP_MODEL_OUTPUT_EXISTS_OR_UNWRITABLE/, 'existing output never overwritten');
open my $prep, '-|', $^X, "$FindBin::Bin/prepare_request.pl", '--cache-info', "$dir/info.txt",
  '--cache-info-sha256', request()->{cacheInfo}->{sha256}, '--request-id', 'prepared-synthetic',
  '--transcript', $target, '--output', "$dir/prepared-request.json" or die $!;
my $prepared_receipt = do {local $/; <$prep>};
close $prep;
is($? >> 8, 0, 'metadata-only preparation CLI succeeds');
my $prepared = records("$dir/prepared-request.json")->[0];
is_deeply($prepared->{requestedTranscripts}, [$target], 'preparation preserves exact version request');
my $receipt = $JSON->decode($prepared_receipt);
is($receipt->{requestSha256}, sha256_hex($JSON->encode($prepared)."\n"), 'preparation reports byte identity');
is_deeply($receipt->{apiSourceSha256}, VarvisTranscriptModelExport::api_source_sha256(), 'preparation pins actually loaded source files');
done_testing;
