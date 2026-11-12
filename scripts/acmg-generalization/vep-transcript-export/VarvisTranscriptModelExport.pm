package VarvisTranscriptModelExport;

use strict;
use warnings;
use base qw(Bio::EnsEMBL::Variation::Utils::BaseVepPlugin);
use Bio::EnsEMBL::ApiVersion;
use Bio::EnsEMBL::Transcript;
use Bio::EnsEMBL::Biotype;
use Bio::EnsEMBL::Translation;
use Bio::EnsEMBL::Exon;
use Bio::EnsEMBL::Slice;
use Bio::EnsEMBL::CoordSystem;
use Digest::SHA qw(sha256_hex);
use Cwd qw(abs_path);
use Fcntl qw(O_WRONLY O_CREAT O_EXCL);
use JSON::PP;
use Scalar::Util qw(blessed refaddr);

# Diagnostic only. No sequence fetch, database lookup, cache write or runtime
# registration. Source objects are inspected without changing their attributes.
my $JSON = JSON::PP->new->canonical->utf8;
my @API_MODULES = qw(Bio/EnsEMBL/ApiVersion.pm Bio/EnsEMBL/Transcript.pm Bio/EnsEMBL/Biotype.pm
  Bio/EnsEMBL/Translation.pm Bio/EnsEMBL/Exon.pm Bio/EnsEMBL/Feature.pm
  Bio/EnsEMBL/Slice.pm Bio/EnsEMBL/CoordSystem.pm
  Bio/EnsEMBL/Variation/Utils/BaseVepPlugin.pm);
my %CODE;
{
  my @amino = split //, 'FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG';
  for my $a (qw(T C A G)) { for my $b (qw(T C A G)) { for my $c (qw(T C A G)) {
    $CODE{$a.$b.$c} = shift @amino;
  } } }
}

sub version { return '116'; }
sub feature_types { return ['Transcript']; }
sub get_header_info { return {}; }
# Scalar arguments prevent a failed terminal regex from returning an empty list
# and sliding the reason string into the truth-value position.
sub _require ($$) {
  die "VEP_MODEL_ASSERTION_ARGUMENT_INVALID\n" unless @_ == 2;
  die $_[1]."\n" unless $_[0];
}
sub _integer { return defined($_[0]) && !ref($_[0]) && $_[0] =~ /^-?[0-9]+$/ && abs($_[0]) <= 2147483647; }
sub _positive { return _integer($_[0]) && $_[0] > 0; }
sub _native_slice {
  my ($slice) = @_;
  # is_circular otherwise consults the restored adaptor when its cache is absent.
  return blessed($slice) && $slice->isa('Bio::EnsEMBL::Slice')
    && (!defined($slice->{adaptor}) || exists($slice->{circular}))
    && (!exists($slice->{circular}) || (defined($slice->{circular}) && !ref($slice->{circular}) && $slice->{circular} eq '0'))
    && !$slice->is_circular && blessed($slice->coord_system)
    && ($slice->coord_system->version || '') eq 'GRCh38'
    && ($slice->coord_system->name || '') eq 'chromosome'
    && _positive($slice->start) && _positive($slice->end) && $slice->start <= $slice->end
    && _integer($slice->strand) && abs($slice->strand) == 1
    && _positive($slice->seq_region_length) && $slice->end <= $slice->seq_region_length;
}
sub _only {
  my ($value, @allowed) = @_;
  return 0 unless ref($value) eq 'HASH';
  my %allowed = map {$_ => 1} @allowed;
  return !grep {!$allowed{$_}} keys %$value;
}
sub _bytes {
  my ($path, $limit) = @_;
  open my $fh, '<:raw', $path or die "VEP_MODEL_SOURCE_UNREADABLE\n";
  my $count = read($fh, my $bytes, $limit + 1);
  close $fh;
  _require(defined($count) && $count <= $limit, 'VEP_MODEL_SOURCE_SIZE_INVALID');
  return $bytes;
}

sub api_source_sha256 {
  my %sha;
  for my $module (@API_MODULES) {
    _require(defined($INC{$module}), 'VEP_MODEL_API_MODULE_MISSING');
    $sha{$module} = sha256_hex(_bytes($INC{$module}, 1_000_000));
  }
  return \%sha;
}

sub new {
  my $class = shift;
  my $self = $class->SUPER::new(@_);
  my %args;
  for my $arg (@{$self->params}) {
    my ($key, $value) = split /=/, $arg, 2;
    _require(defined($value) && length($value) && !exists($args{$key}), 'VEP_MODEL_ARGUMENT_INVALID');
    $args{$key} = $value;
  }
  _require(_only(\%args, qw(request request_sha256 output)) && keys(%args) == 3, 'VEP_MODEL_ARGUMENT_INVALID');
  my $config = $self->config;
  _require(Bio::EnsEMBL::ApiVersion::software_version() == 116 && $config->{offline} && $config->{cache}
    && !$config->{database} && !$config->{fork} && !$config->{gff} && !$config->{gtf} && !$config->{refseq}
    && !$config->{merged} && ($config->{assembly} || '') eq 'GRCh38'
    && ($config->{species} || '') eq 'homo_sapiens', 'VEP_MODEL_OFFLINE_NATIVE_116_REQUIRED');
  _require((!defined($config->{cache_version}) || $config->{cache_version} == 116)
    && (!defined($config->{db_version}) || $config->{db_version} == 116), 'VEP_MODEL_CACHE_VERSION_MISMATCH');
  my $bytes = _bytes($args{request}, 100_000);
  _require(sha256_hex($bytes) eq $args{request_sha256}, 'VEP_MODEL_REQUEST_SHA_MISMATCH');
  my $request = $JSON->decode($bytes);
  (my $compact = $bytes) =~ s/\n$//;
  _require($compact eq $JSON->encode($request), 'VEP_MODEL_REQUEST_CANONICAL_REQUIRED');
  _require(_only($request, qw(schemaVersion requestId assembly species cacheVersion requestedTranscripts cacheInfo apiSourceSha256))
    && ($request->{schemaVersion} || '') eq 'vep116-transcript-export-request-v1'
    && ($request->{requestId} || '') =~ /^[A-Za-z0-9_.-]+$/
    && ($request->{assembly} || '') eq 'GRCh38' && ($request->{species} || '') eq 'homo_sapiens'
    && ($request->{cacheVersion} || '') eq '116_GRCh38', 'VEP_MODEL_REQUEST_SCHEMA_INVALID');
  my $targets = $request->{requestedTranscripts};
  _require(ref($targets) eq 'ARRAY' && @$targets > 0 && @$targets <= 32, 'VEP_MODEL_REQUEST_COUNT_INVALID');
  for my $target (@$targets) {
    _require(!ref($target) && $target =~ /^(ENST[0-9]+)\.([1-9][0-9]*)$/ && !exists($self->{_requested}->{$target}),
      'VEP_MODEL_EXACT_TRANSCRIPT_REQUEST_REQUIRED');
    $self->{_requested}->{$target} = {status => 'NOT_OBSERVED', observations => 0};
    push @{$self->{_by_accession}->{$1}}, $target;
  }
  my $info = $request->{cacheInfo};
  _require(_only($info, qw(path sha256)) && ($info->{sha256} || '') =~ /^[0-9a-f]{64}$/, 'VEP_MODEL_CACHE_INFO_BINDING_INVALID');
  my $root = $config->{dir_cache} || $config->{dir};
  my $effective_dir = $config->{full_cache_dir} || (defined($root) ? $root.'/homo_sapiens/116_GRCh38' : '');
  my $effective_info = length($effective_dir) ? abs_path($effective_dir.'/info.txt') : undef;
  my $requested_info = defined($info->{path}) && !ref($info->{path}) ? abs_path($info->{path}) : undef;
  _require(defined($effective_info) && defined($requested_info) && $effective_info eq $requested_info,
    'VEP_MODEL_EFFECTIVE_CACHE_INFO_MISMATCH');
  my $info_bytes = _bytes($info->{path}, 100_000);
  _require(sha256_hex($info_bytes) eq $info->{sha256}, 'VEP_MODEL_CACHE_INFO_SHA_MISMATCH');
  my %info_fields;
  for my $line (split /\r?\n/, $info_bytes) {
    next unless length $line;
    my ($key, $value) = split /\t/, $line, 2;
    _require(defined($value) && !exists($info_fields{$key}), 'VEP_MODEL_CACHE_INFO_AMBIGUOUS');
    $info_fields{$key} = $value;
  }
  _require(($info_fields{species} || '') eq 'homo_sapiens' && ($info_fields{assembly} || '') eq 'GRCh38',
    'VEP_MODEL_CACHE_INFO_SCOPE_MISMATCH');
  my $api = api_source_sha256();
  _require($JSON->encode($api) eq $JSON->encode($request->{apiSourceSha256}), 'VEP_MODEL_API_SOURCE_MISMATCH');
  sysopen(my $out, $args{output}, O_WRONLY | O_CREAT | O_EXCL) or die "VEP_MODEL_OUTPUT_EXISTS_OR_UNWRITABLE\n";
  binmode($out, ':raw');
  $self->{_output} = $out;
  $self->{_request} = $request;
  $self->_write({recordType => 'HEADER', schemaVersion => 'vep116-transcript-source-export-v1',
    requestId => $request->{requestId}, requestSha256 => $args{request_sha256}, requestedTranscripts => $targets,
    sourceKind => 'VEP116_OFFLINE_CACHE_TRANSCRIPT_OBJECT_NOT_REST',
    cacheVersion => $request->{cacheVersion}, cacheInfoSha256 => $info->{sha256}, apiSourceSha256 => $api,
    exporterVersion => '1.0.1', exporterSha256 => sha256_hex(_bytes(__FILE__, 1_000_000)),
    sequenceIndependence => 'NO_INDEPENDENT_RNA_SEQUENCE_CLAIM', cacheDataIntegrity => 'INFO_METADATA_ONLY_NOT_ALL_CACHE_SHARDS',
    defaultEnabled => JSON::PP::false, clinicalAcceptance => JSON::PP::false});
  return $self;
}

sub _write {
  my ($self, $record) = @_;
  my $out = $self->{_output};
  _require(defined($out) && print($out $JSON->encode($record)."\n"), 'VEP_MODEL_EXPORT_WRITE_FAILED');
}

sub run {
  my ($self, $tva) = @_;
  my $tr = $tva->transcript;
  return {} unless blessed($tr) && $tr->isa('Bio::EnsEMBL::Transcript');
  my $accession = $tr->stable_id;
  return {} unless defined($accession) && exists($self->{_by_accession}->{$accession});
  for my $target (@{$self->{_by_accession}->{$accession}}) {
    my $state = $self->{_requested}->{$target};
    $state->{observations}++;
    next if $state->{status} eq 'CONFLICT';
    my $body;
    eval { $body = extract_transcript($tr, $target); 1 } or do {
      my $reason = $@ =~ /^(VEP_MODEL_[A-Z0-9_]+)/ ? $1 : 'VEP_MODEL_EXTRACTION_FAILED';
      $body = {status => 'REJECTED', reasonCode => $reason};
    };
    my $digest = sha256_hex($JSON->encode($body));
    if (exists($state->{sha256})) {
      if ($state->{sha256} ne $digest) {
        $state->{status} = 'CONFLICT';
        $self->_write({recordType => 'CONFLICT', requestedTranscript => $target,
          reasonCode => 'VEP_MODEL_MULTIPLE_OBJECT_IDENTITIES', previousSha256 => $state->{sha256}, otherSha256 => $digest});
      }
      next;
    }
    $state->{sha256} = $digest;
    $state->{status} = $body->{status};
    $self->_write({recordType => 'TRANSCRIPT', requestedTranscript => $target, objectSha256 => $digest, body => $body});
  }
  return {};
}

sub finish {
  my ($self) = @_;
  return if $self->{_finished};
  my @summary = map {{requestedTranscript => $_, %{$self->{_requested}->{$_}}}} @{$self->{_request}->{requestedTranscripts}};
  $self->_write({recordType => 'FOOTER', status => 'EXPORT_COMPLETED', transcripts => \@summary});
  close($self->{_output}) or die "VEP_MODEL_EXPORT_CLOSE_FAILED\n";
  $self->{_finished} = 1;
}

sub extract_transcript {
  my ($tr, $target) = @_;
  _require(blessed($tr) && $tr->isa('Bio::EnsEMBL::Transcript') && exists($tr->{version}) && _positive($tr->version)
    && defined($tr->stable_id) && $tr->stable_id.'.'.$tr->version eq $target, 'VEP_MODEL_TRANSCRIPT_VERSION_MISMATCH');
  # Earlier VEP callers may already have resolved the cached string into a
  # Biotype object. Inspect only existing fields; biotype()/get_Biotype() can
  # default a missing name or consult the restored adaptor.
  my $biotype = $tr->{biotype};
  if (ref($biotype) eq 'Bio::EnsEMBL::Biotype') {
    _require(defined($biotype->{object_type}) && !ref($biotype->{object_type})
      && $biotype->{object_type} eq 'transcript', 'VEP_MODEL_PROTEIN_CODING_REQUIRED');
    $biotype = $biotype->{name};
  }
  _require(defined($biotype) && !ref($biotype) && $biotype eq 'protein_coding',
    'VEP_MODEL_PROTEIN_CODING_REQUIRED');
  _require(ref($tr->{_trans_exon_array}) eq 'ARRAY' && @{$tr->{_trans_exon_array}} > 0
    && @{$tr->{_trans_exon_array}} <= 1000 && blessed($tr->{translation}), 'VEP_MODEL_CACHED_EXONS_OR_TRANSLATION_MISSING');
  _require(ref($tr->{attributes}) eq 'ARRAY', 'VEP_MODEL_CACHED_ATTRIBUTES_MISSING');
  for my $attribute (@{$tr->{attributes}}) {
    _require(blessed($attribute) && defined($attribute->code), 'VEP_MODEL_ATTRIBUTE_INVALID');
    _require($attribute->code !~ /^(?:_rna_edit|_transl_start|_transl_end|cds_start_NF|cds_end_NF)$/i,
      'VEP_MODEL_EDITED_OR_INCOMPLETE_CDS_UNSUPPORTED');
  }
  my $cache = $tr->{_variation_effect_feature_cache};
  _require(ref($cache) eq 'HASH' && ref($cache->{seq_edits}) eq 'ARRAY' && !@{$cache->{seq_edits}}
    && defined($cache->{codon_table}) && $cache->{codon_table} == 1, 'VEP_MODEL_CACHED_TRANSLATION_EDITS_OR_CODE_UNSUPPORTED');
  my ($cds, $peptide) = @{$cache}{qw(translateable_seq peptide)};
  _require(defined($cds) && !ref($cds) && $cds =~ /^ATG(?:[ACGT]{3})*(?:TAA|TAG|TGA)$/
    && length($cds) <= 1_000_000 && defined($peptide) && !ref($peptide) && $peptide =~ /^[ACDEFGHIKLMNPQRSTVWY]+$/,
    'VEP_MODEL_CACHED_CDS_OR_PEPTIDE_INCOMPLETE');
  my $translated = '';
  for (my $i = 0; $i < length($cds); $i += 3) { $translated .= $CODE{substr($cds, $i, 3)}; }
  _require($translated eq $peptide.'*', 'VEP_MODEL_CACHED_CDS_PEPTIDE_MISMATCH');
  my $slice = $tr->slice;
  _require(_native_slice($slice), 'VEP_MODEL_NATIVE_SLICE_REQUIRED');
  my ($contig, $strand) = ($tr->seq_region_name, $tr->seq_region_strand);
  _require(defined($contig) && $contig =~ /^(?:[1-9]|1[0-9]|2[0-2]|X|Y)$/ && _integer($strand) && abs($strand) == 1,
    'VEP_MODEL_PRIMARY_CONTIG_OR_STRAND_INVALID');
  my $tl = $tr->translation;
  _require($tl->isa('Bio::EnsEMBL::Translation') && exists($tl->{version}) && _positive($tl->version)
    && defined($tl->stable_id) && $tl->stable_id =~ /^ENSP[0-9]+$/, 'VEP_MODEL_PROTEIN_VERSION_MISSING');
  my ($start_exon, $end_exon) = ($tl->start_Exon, $tl->end_Exon);
  _require(blessed($start_exon) && blessed($end_exon) && _positive($tl->start) && _positive($tl->end),
    'VEP_MODEL_TRANSLATION_BOUNDARY_MISSING');
  my @exons = @{$tr->get_all_Exons};
  my (@output, %ids, $previous, $cdna_start, $cdna_end, $coding_start, $coding_end, $total);
  $total = 0;
  for my $exon (@exons) {
    _require(blessed($exon) && $exon->isa('Bio::EnsEMBL::Exon') && _native_slice($exon->slice),
      'VEP_MODEL_NATIVE_EXON_REQUIRED');
    my ($lo, $hi, $id) = ($exon->seq_region_start, $exon->seq_region_end, $exon->stable_id);
    _require(_positive($lo) && _positive($hi) && $lo <= $hi && $hi <= $exon->slice->seq_region_length
      && defined($id) && $id =~ /^ENSE[0-9]+$/ && !$ids{$id}++
      && $exon->seq_region_name eq $contig && $exon->seq_region_strand == $strand, 'VEP_MODEL_EXON_IDENTITY_INVALID');
    _require(!defined($previous) || ($strand == 1 ? $lo > $previous->{end} : $hi < $previous->{start}), 'VEP_MODEL_EXON_ORDER_OR_OVERLAP');
    my $length = $hi - $lo + 1;
    _require($exon->length == $length, 'VEP_MODEL_EXON_LENGTH_MISMATCH');
    if (refaddr($exon) == refaddr($start_exon)) {
      _require(!defined($cdna_start) && $tl->start <= $length, 'VEP_MODEL_TRANSLATION_START_INVALID');
      $cdna_start = $total + $tl->start;
      $coding_start = $strand == 1 ? $lo + $tl->start - 1 : $hi - $tl->start + 1;
    }
    if (refaddr($exon) == refaddr($end_exon)) {
      _require(!defined($cdna_end) && $tl->end <= $length, 'VEP_MODEL_TRANSLATION_END_INVALID');
      $cdna_end = $total + $tl->end;
      $coding_end = $strand == 1 ? $lo + $tl->end - 1 : $hi - $tl->end + 1;
    }
    my $entry = {id => $id, ordinal => scalar(@output) + 1, start => 0+$lo, end => 0+$hi, strand => 0+$strand,
      assembly_name => 'GRCh38', seq_region_name => "$contig", cdnaStart => $total + 1, cdnaEnd => $total + $length,
      exonVersionStatus => 'CACHE_DOES_NOT_PRESERVE_EXON_VERSION'};
    push @output, $entry; $previous = $entry; $total += $length;
    _require($total <= 1_000_000, 'VEP_MODEL_TRANSCRIPT_TOO_LONG');
  }
  _require(defined($cdna_start) && defined($cdna_end) && $cdna_start <= $cdna_end
    && $cdna_end - $cdna_start + 1 == length($cds), 'VEP_MODEL_CDS_BOUNDARIES_INCOMPLETE');
  _require(exists($tr->{cdna_coding_start}) && exists($tr->{cdna_coding_end})
    && _positive($tr->{cdna_coding_start}) && _positive($tr->{cdna_coding_end})
    && $tr->cdna_coding_start == $cdna_start && $tr->cdna_coding_end == $cdna_end,
    'VEP_MODEL_CACHED_CDNA_BOUNDARY_MISMATCH');
  my ($min, $max) = ($output[0]->{start}, $output[-1]->{end});
  ($min, $max) = ($output[-1]->{start}, $output[0]->{end}) if $strand == -1;
  _require($tr->seq_region_start == $min && $tr->seq_region_end == $max && $tr->length == $total,
    'VEP_MODEL_FULL_TRANSCRIPT_EXON_SPAN_MISMATCH');
  my @cds_bounds = sort {$a <=> $b} ($coding_start, $coding_end);
  my $lookup = {id => $tr->stable_id, version => 0+$tr->version, object_type => 'Transcript', biotype => 'protein_coding',
    assembly_name => 'GRCh38', species => 'homo_sapiens', seq_region_name => "$contig", strand => 0+$strand,
    start => $min, end => $max, length => $total, Exon => \@output,
    Translation => {id => $tl->stable_id, version => 0+$tl->version, Parent => $tr->stable_id,
      start => $cds_bounds[0], end => $cds_bounds[1], length => length($peptide)}};
  return {status => 'EXPORTED_UNIQUE', lookup => $lookup,
    codingBoundaries => {cdnaStart => $cdna_start, cdnaEnd => $cdna_end, genomicBiologicalStart => $coding_start,
      genomicBiologicalEnd => $coding_end, cdsLengthIncludingStop => length($cds), proteinLength => length($peptide),
      translationStartExon => $start_exon->stable_id, translationEndExon => $end_exon->stable_id,
      translationStartExonOffset => 0+$tl->start, translationEndExonOffset => 0+$tl->end},
    objectSequenceChecks => {cdsSha256 => sha256_hex($cds), peptideSha256 => sha256_hex($peptide),
      source => 'ALREADY_PRESENT_VEP_OBJECT_CACHE_NO_SEQUENCE_GETTERS_CALLED', independence => 'NOT_INDEPENDENT_RNA'},
    reviewStatus => 'EXTRACTED_PENDING_INDEPENDENT_REFSEQ_AND_NATIVE_FASTA_CHECK'};
}

1;
