"""Read-only identity and sequence verification of HNF1A truncating/NMD.

Uses the frozen evidence schemas and identity projections, but independently
edits labelled CDS bases and translates the standard genetic code. No Java
resolver or evaluator is invoked. Clinical classification is not assessed.

Usage: python scripts/pvs1/verify_hnf1a_frameshift_business.py [artifact-directory]
The directory is the network client's hnf1a-frameshift directory. All four
public controls and pre/post JVM-restart response/counter records are required.
"""
import argparse
import calendar
import copy
from datetime import datetime
from decimal import Decimal
import hashlib
import itertools
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.evidence import acmg_evidence_v2_contract as e1
from scripts.e3_p1a.validate_acmg_rule_registry_v2 import _validate_schema

RES = ROOT / 'varvis-server/src/main/resources/acmg'
CONTROLS = {(120989017, 'C', 'T'), (120993678, 'C', 'T'),
            (120994314, 'G', 'GC'), (120993577, 'CA', 'C')}
CODE = dict(zip(map(''.join, itertools.product('TCAG', repeat=3)),
                'FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG', strict=True))
AA = dict(zip('ACDEFGHIKLMNPQRSTVWY*',
              ('Ala', 'Cys', 'Asp', 'Glu', 'Phe', 'Gly', 'His', 'Ile', 'Lys', 'Leu',
               'Met', 'Asn', 'Pro', 'Gln', 'Arg', 'Ser', 'Thr', 'Val', 'Trp', 'Tyr', 'Ter'), strict=True))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def equal(actual, expected, label):
    require(actual == expected, f'{label}: expected {expected!r}, observed {actual!r}')


def read(path, decimals=False):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'), parse_float=Decimal if decimals else float)


def sha(value):
    return hashlib.sha256(value.encode('ascii') if isinstance(value, str) else value).hexdigest()


def verify_vep_measurements(rows, expected):
    """VEP textual measurements require rawValue; normalizedValue is optional."""
    measurements = {row['metricId']: row for row in rows}
    equal(len(measurements), len(rows), 'unique VEP measurements')
    for metric, value in expected.items():
        require(metric in measurements, 'missing VEP measurement ' + metric)
        row = measurements[metric]
        equal(row['rawValue'], value, 'VEP evidence/annotation raw ' + metric)
        if 'normalizedValue' in row:
            equal(row['normalizedValue'], value, 'VEP evidence/annotation normalized ' + metric)


def timestamp_seconds(value):
    """Compare Jackson ISO instants or numeric epochs without losing nanoseconds."""
    if isinstance(value, (Decimal, int)) and not isinstance(value, bool):
        return Decimal(value)
    require(isinstance(value, str), 'timestamp must preserve exact decimal or ISO value')
    match = re.fullmatch(r'(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z', value)
    require(match is not None, 'timestamp must be a UTC instant with at most nine fractional digits')
    seconds = Decimal(calendar.timegm(datetime.fromisoformat(match[1]).timetuple()))
    return seconds + Decimal('0.' + (match[2] or '0'))


def _verify_original_profile():
    profile = read(RES / 'pvs1-hnf1a-business-profile-v2.json')
    before = read(RES / 'pvs1-hnf1a-business-profile-v1.json')
    model = read(RES / 'pvs1-hnf1a-transcript-model-v1.json')
    equal(profile['predecessorProfileSha256'], e1.canonical_sha256(before), 'predecessor profile')
    equal(profile['predecessorProfileId'], before['profileId'], 'predecessor ID')
    equal(profile['profileVersion'], '2.0.0', 'profile version')
    for key in ('activation', 'context', 'transcriptModel', 'resources'):
        equal(profile[key], before[key], 'preserved ' + key)
    for key, value in before['policy'].items():
        if key != 'policyId':
            equal(profile['policy'][key], value, 'preserved policy ' + key)
    equal(profile['activation']['automaticTrigger'], False, 'explicit execution')
    equal(profile['activation']['classificationEnabled'], False, 'classification disabled')
    fs = profile['policy']['frameshift']
    equal((fs['maximumAlleleLengthIncludingAnchor'], fs['maximumInsertedOrDeletedBases'],
           fs['minimumDistanceToLastJunction']), (50, 49, 55), 'engineering bounds')
    registry_path = RES.parent / profile['registryBinding']['registryClasspathResource'].lstrip('/')
    registry = read(registry_path)
    _validate_schema(registry)
    old_registry = read(RES / 'rule-registry-v2/acmg-rule-registry-pvs1-hnf1a-2026-09-09.json')
    equal(registry['lineage'], old_registry['lineage'], 'frozen registry lineage')
    equal(registry['lineage']['baseRegistrySha256'], e1.canonical_sha256(read(e1.RULE_REGISTRY_PATH)), 'base registry')
    equal(set(registry), set(old_registry), 'registry fields')
    for key in registry:
        if key not in ('registryId', 'criteria'):
            equal(registry[key], old_registry[key], 'preserved registry ' + key)
    for old, new in zip(old_registry['criteria'], registry['criteria'], strict=True):
        equal({k: v for k, v in new.items() if k != 'notes' or new['code'] != 'PVS1'},
              {k: v for k, v in old.items() if k != 'notes' or old['code'] != 'PVS1'}, 'other criteria unchanged')
    equal(registry['registryId'], profile['registryBinding']['registryId'], 'registry ID')
    equal(sha(registry_path.read_bytes()), profile['registryBinding']['registrySha256'], 'registry byte hash')
    equal(e1.canonical_sha256(model), profile['transcriptModel']['canonicalSha256'], 'transcript model hash')
    equal((model['transcript'], model['refseqTranscript'], model['strand'], model['assembly']),
          ('ENST00000257555.11', 'NM_000545.8', 1, 'GRCh38'), 'transcript scope')
    equal(len(model['cdsSequence']), 1896, 'CDS length')
    equal(model['exons'][8]['cdsEnd'], model['lastJunctionCdsPosition'], 'last junction')
    equal(model['lastJunctionCdsPosition'], 1768, 'last junction position')
    joint = read(RES / 'acmg-business-execution-profile-joint-v2.json')
    old_joint = read(RES / 'acmg-business-execution-profile-joint-v1.json')
    equal(joint['enabledCriteria'], old_joint['enabledCriteria'], 'eight enabled rules')
    for old, new in zip(old_joint['slices'], joint['slices'], strict=True):
        if old['criterionIds'] != ['ACMG_AMP_2015:PVS1']:
            equal(new, old, 'other business slices unchanged')
        else:
            equal(new['profileCanonicalSha256'], e1.canonical_sha256(profile), 'joint PVS1 binding')
            equal(new['registryBinding'], profile['registryBinding'], 'joint registry binding')
    return profile, model


def verify_profile(profile_sha256=None):
    """Select only a registered policy identity, including immutable old v2."""
    previous, model = _verify_original_profile()
    current = read(RES / 'pvs1-hnf1a-business-profile-v3.json')
    equal(current['profileVersion'], '2.0.1', 'current implementation version')
    equal(current['predecessorProfileId'], previous['profileId'], 'implementation predecessor ID')
    equal(current['predecessorProfileVersion'], previous['profileVersion'], 'implementation predecessor version')
    equal(current['predecessorProfileSha256'], e1.canonical_sha256(previous), 'implementation predecessor hash')
    changed_fields = {'profileVersion', 'predecessorProfileId', 'predecessorProfileVersion',
                      'predecessorProfileSha256', 'implementation'}
    equal({k: v for k, v in current.items() if k not in changed_fields},
          {k: v for k, v in previous.items() if k not in changed_fields},
          'scientific policy and resources unchanged by peptide fix')
    implementation = current['implementation']
    equal(implementation['evaluatorVersion'], current['profileVersion'], 'implementation evaluator version')
    equal(implementation['scientificPolicyChanged'], False, 'implementation-only revision')
    equal(implementation['sourceCommit'], 'db7394a02523a6e519cb0d89f84b2889bac12737',
          'verified Ensembl 116 source revision')
    equal(implementation['localPeptide'],
          'TRANSLATE_ALL_COMPLETE_LOCAL_CODONS; APPEND_X_FOR_PARTIAL_UNLESS_PEPTIDE_IS_SINGLE_STOP; '
          'EMPTY_IS_DASH; EQUAL_PEPTIDES_COLLAPSE', 'registered VEP local peptide semantics')
    joint = read(RES / 'acmg-business-execution-profile-joint-v3.json')
    old_joint = read(RES / 'acmg-business-execution-profile-joint-v2.json')
    equal(joint['profileId'], 'acmg-explicit-business-joint-v3', 'current joint ID')
    equal(joint['manifestVersion'], '1.3.1', 'current joint version')
    equal(joint['enabledCriteria'], old_joint['enabledCriteria'], 'eight criteria after peptide fix')
    for old, new in zip(old_joint['slices'], joint['slices'], strict=True):
        if old['criterionIds'] == ['ACMG_AMP_2015:PVS1']:
            equal(new['profileVersion'], current['profileVersion'], 'current joint PVS1 version')
            equal(new['profileCanonicalSha256'], e1.canonical_sha256(current), 'current joint PVS1 hash')
            equal(new['registryBinding'], current['registryBinding'], 'unchanged joint registry')
        else:
            equal(new, old, 'other business slices unchanged by peptide fix')
    profiles = {e1.canonical_sha256(p): p for p in (previous, current)}
    selected_hash = profile_sha256 or e1.canonical_sha256(current)
    require(selected_hash in profiles, 'unregistered saved PVS1 profile identity')
    return profiles[selected_hash], model


def verify_execution_profile(document, profile):
    """A saved joint document retains the manifest that produced its evidence."""
    revision = {'2.0.0': 2, '2.0.1': 3}[profile['profileVersion']]
    expected = read(RES / f'acmg-business-execution-profile-joint-v{revision}.json')
    for key, value in expected.items():
        equal(document.get(key), value, 'saved execution profile ' + key)
    identity = {k: v for k, v in document.items() if k != 'manifestSha256'}
    equal(document['manifestSha256'], e1.canonical_sha256(identity), 'saved execution manifest hash')


def translate(sequence):
    protein = ''
    for offset in range(0, len(sequence) - 2, 3):
        protein += CODE[sequence[offset:offset + 3]]
        if protein[-1] == '*':
            break
    return protein


def vep_local_peptide(sequence):
    """VEP 116 peptide(), for the independently derived unedited nuclear CDS.

    Ensembl TranscriptVariationAllele.pm translates every complete local triplet,
    then appends X for a remainder unless that entire peptide is exactly '*'.
    This is distinct from translate(), which stops at the first full-CDS PTC.
    Empty codons are represented by '-' by codon(), before peptide() is called.
    """
    require(isinstance(sequence, str), 'local codon sequence must be text')
    sequence = '' if sequence == '-' else sequence.upper()
    require(re.fullmatch('[ACGT]*', sequence) is not None, 'unambiguous local codon sequence')
    complete = len(sequence) // 3 * 3
    peptide = ''.join(CODE[sequence[i:i + 3]] for i in range(0, complete, 3))
    if complete != len(sequence) and peptide != '*':
        peptide += 'X'
    return peptide or '-'


def vep_peptide_alleles(reference, alternate):
    """pep_allele_string() collapses identical local peptides to one string."""
    ref, alt = vep_local_peptide(reference), vep_local_peptide(alternate)
    return alt if ref == alt else ref + '/' + alt


def labelled_cds(model):
    """Each reference base carries its 1-based CDS position and exon ordinal."""
    result = []
    for position, base in enumerate(model['cdsSequence'], 1):
        matches = [e['ordinal'] for e in model['exons'] if e['cdsStart'] <= position <= e['cdsEnd']]
        require(len(matches) == 1, 'CDS base must belong to exactly one exon')
        result.append((base, position, matches[0]))
    return result


def coding_edit(hgvs, model, exon_ordinal):
    """Return a validated half-open CDS edit; it must stay in the allele exon."""
    prefix, separator, text = hgvs.partition(':c.')
    equal((prefix, separator), (model['transcript'], ':c.'), 'versioned coding HGVS')
    match = re.fullmatch(r'([1-9][0-9]*)(?:_([1-9][0-9]*))?(del|dup)([ACGT]*)', text)
    cds = model['cdsSequence']
    if match:
        first, last = int(match[1]), int(match[2] or match[1])
        require(1 <= first <= last <= len(cds) - 3, 'coding HGVS span')
        sequence = cds[first - 1:last]
        if match[4]:
            equal(match[4], sequence, 'HGVS deleted/duplicated bases')
        start, end, inserted = (first - 1, last, '') if match[3] == 'del' else (last, last, sequence)
    else:
        match = re.fullmatch(r'([1-9][0-9]*)_([1-9][0-9]*)ins([ACGT]+)', text)
        require(match is not None, 'unsupported coding HGVS')
        first, last = int(match[1]), int(match[2])
        require(last == first + 1 and 1 <= first < last <= len(cds) - 3, 'insertion flanks')
        start, end, inserted = first, first, match[3]
    labels = labelled_cds(model)
    affected = labels[start:end] if start != end else labels[start - 1:start + 1]
    equal({base[2] for base in affected}, {exon_ordinal}, 'HGVS exon boundary')
    return start, end, inserted


def derive_science(allele, annotation, model, policy):
    """Independent scientific reconstruction, including unshifted VEP local fields."""
    equal((allele['assembly'], allele['contig']), ('GRCh38', '12'), 'native allele')
    equal(model['strand'], 1, 'only the fixed HNF1A positive strand is supported')
    ref, alt, genomic = allele['reference'], allele['alternate'], allele['position']
    require(re.fullmatch('[ACGT]{1,50}', ref) and re.fullmatch('[ACGT]{1,50}', alt), 'bounded allele')
    exons = [e for e in model['exons'] if e['genomicStart'] <= genomic
             and genomic + len(ref) - 1 <= e['genomicEnd']]
    require(len(exons) == 1, 'full REF interval crosses an exon boundary')
    exon = exons[0]
    c = exon['cdsStart'] + genomic - exon['genomicStart']
    cds = model['cdsSequence']
    require(1 <= c and c + len(ref) - 1 <= len(cds) - 3, 'edit is not entirely before native stop')
    equal(cds[c - 1:c - 1 + len(ref)], ref, 'full CDS REF including anchor')
    equal(annotation['exon'], f"{exon['ordinal']}/{len(model['exons'])}", 'VEP exon')
    terms = set(re.split(r'[,&]', annotation['consequence']))
    require(not any('splice' in term or term == 'intron_variant' for term in terms), 'splice branch is excluded')
    labels = labelled_cds(model)
    is_snv = len(ref) == len(alt) == 1 and ref != alt
    if is_snv:
        require('stop_gained' in terms, 'SNV must be stop-gained')
        equal(annotation['hgvsCoding'], f"{model['transcript']}:c.{c}{ref}>{alt}", 'SNV HGVS')
        start, end, inserted = c - 1, c, alt
        edited = labels[:start] + [(alt, c, exon['ordinal'])] + labels[end:]
    else:
        require('frameshift_variant' in terms, 'indel must be annotated frameshift')
        require((len(ref) == 1 < len(alt) or len(alt) == 1 < len(ref)) and ref[0] == alt[0], 'simple anchored indel')
        require(ref[-1] != alt[-1], 'indel must be normalized')
        require((len(alt) - len(ref)) % 3 != 0, 'in-frame indel')
        start, end, inserted = c, c + len(ref) - 1, alt[1:]
        if start == end:
            equal({base[2] for base in labels[start - 1:start + 1]}, {exon['ordinal']}, 'insertion exon flanks')
        edited = labels[:start] + [(b, None, exon['ordinal']) for b in inserted] + labels[end:]
    changed = ''.join(base[0] for base in edited)
    original_protein, altered_protein = translate(cds), translate(changed)
    require('*' in altered_protein, 'no stop found in verified CDS; do not invent UTR sequence')
    first = next((i for i, pair in enumerate(zip(original_protein, altered_protein)) if pair[0] != pair[1]), None)
    require(first is not None and first < len(original_protein) - 1, 'no premature protein change')
    stop = altered_protein.index('*')
    require(first <= stop, 'stop before first changed amino acid')
    ptc_start, ptc_end = stop * 3 + 1, stop * 3 + 3
    ptc_bases = edited[ptc_start - 1:ptc_end]
    require(len(ptc_bases) == 3 and len({b[2] for b in ptc_bases}) == 1, 'PTC spans exon junction')
    ptc_exon = ptc_bases[0][2]
    last_junction = sum(base[2] < len(model['exons']) for base in edited)
    native_stop = next(i for i, base in enumerate(edited, 1) if base[1] == len(cds) - 2)
    require(ptc_start < native_stop, 'new stop is not before shifted native termination region')
    predicted = f"{model['protein']}:p.{AA[original_protein[first]]}{first + 1}"
    predicted += 'Ter' if altered_protein[first] == '*' else f'{AA[altered_protein[first]]}fsTer{stop - first + 1}'
    equal(annotation['hgvsProtein'], predicted, 'independently translated HGVSp')
    output = dict(cdsPosition=c if is_snv else c + 1, exonOrdinal=exon['ordinal'], exonTotal=len(model['exons']),
                  refCodon=cds[first * 3:first * 3 + 3], altCodon=changed[first * 3:first * 3 + 3],
                  proteinPosition=first + 1, ptcStart=ptc_start, ptcEnd=ptc_end,
                  lastJunctionCdsPosition=last_junction, distanceToLastJunction=last_junction - ptc_end,
                  ensemblTranscript=model['transcript'], refseqTranscript=model['refseqTranscript'])
    if is_snv:
        equal(annotation['proteinPosition'], str(first + 1), 'SNV VEP protein position')
        equal(annotation['aminoAcids'], original_protein[first] + '/*', 'SNV VEP amino acids')
        equal(annotation['codons'].upper(), output['refCodon'] + '/' + output['altCodon'], 'SNV VEP codons')
        require(ptc_end < policy['exclusivePtcEnd'], 'SNV NMD escape branch')
    else:
        hgvs_start, hgvs_end, hgvs_inserted = coding_edit(annotation['hgvsCoding'], model, exon['ordinal'])
        equal(cds[:hgvs_start] + hgvs_inserted + cds[hgvs_end:], changed, 'complete HGVS/VCF CDS equivalence')
        # VEP's raw local codon fields refer to the input interval, independently
        # of any unchanged peptide prefix or HGVS 3-prime movement in a repeat.
        if start == end and start % 3 == 0:
            protein_position = f'{start // 3}-{start // 3 + 1}'
            local_ref, local_alt = '', inserted
        else:
            touched = range(start // 3, (max(start + 1, end) - 1) // 3 + 1)
            lo, hi = touched.start * 3, touched.stop * 3
            protein_position = str(touched.start + 1) if len(touched) == 1 else f'{touched.start + 1}-{touched.stop}'
            local_ref = cds[lo:hi]
            local_alt = cds[lo:start] + inserted + cds[end:hi]
        codons = (local_ref or '-') + '/' + (local_alt or '-')
        amino_acids = vep_peptide_alleles(local_ref, local_alt)
        equal(annotation['proteinPosition'], protein_position, 'raw VEP protein span')
        equal(annotation['aminoAcids'], amino_acids, 'raw VEP amino acids')
        equal(annotation['codons'].upper(), codons, 'raw VEP local codons')
        delta = len(changed) - len(cds)
        exon_lengths = {e['ordinal']: e['genomicEnd'] - e['genomicStart'] + 1
                        + (delta if e['ordinal'] == exon['ordinal'] else 0) for e in model['exons']}
        output.update(variantMechanism='FRAMESHIFT', coordinateSystem='ALTERED_CDS', netLengthChange=delta,
                      anchorReferenceCdsPosition=c, referenceEditStart0=start, referenceEditEnd0=end,
                      deletedSequence=ref[1:], insertedSequence=inserted, referenceCdsSha256=sha(cds),
                      alteredCdsSequence=changed, alteredCdsSha256=sha(changed), alteredCdsLength=len(changed),
                      hgvscSequenceEquivalent=True, hgvscReferenceEditStart0=hgvs_start,
                      hgvscReferenceEditEnd0=hgvs_end, hgvscInsertedSequence=hgvs_inserted,
                      firstChangedProteinPosition=first + 1, terminationDistance=stop - first + 1,
                      referenceLastJunctionCdsPosition=model['lastJunctionCdsPosition'],
                      mutantLastJunctionCdsPosition=last_junction, alteredProteinThroughTermination=altered_protein,
                      ptcReferenceStart=ptc_bases[0][1], ptcReferenceMiddle=ptc_bases[1][1],
                      ptcReferenceEnd=ptc_bases[-1][1],
                      ptcReferenceMapping='REFERENCE_BASES' if all(b[1] is not None for b in ptc_bases)
                      else 'CONTAINS_INSERTED_BASES', ptcExonOrdinal=ptc_exon,
                      alteredVariantExonLength=exon_lengths[exon['ordinal']], alteredPtcExonLength=exon_lengths[ptc_exon],
                      sequenceDerivationStatus='GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE')
        require(last_junction - ptc_end >= policy['frameshift']['minimumDistanceToLastJunction'], 'indel NMD escape branch')
        require(max(exon_lengths[exon['ordinal']], exon_lengths[ptc_exon]) <= policy['maximumExonLength'], 'long exon branch')
    require(output['cdsPosition'] >= policy['minimumCdsPosition'], 'start-proximal branch')
    require(policy['firstExon'] <= exon['ordinal'] <= policy['lastExon'] and ptc_exon <= policy['lastExon'], 'outside enabled exon scope')
    output.update(nmdPrediction='PREDICTED_NMD', exonRelevance='GN017_DISEASE_TRANSCRIPT_EXONS_1_TO_9',
                  mechanism='HNF1A_MONOGENIC_DIABETES_LOSS_OF_FUNCTION')
    return output


def verify_envelope(envelope, formal_candidate=None, *, profile=None, model=None, schemas=None):
    """Verify one positive envelope without mutating supplied Java JSON nodes."""
    if profile is None or model is None:
        profile, model = verify_profile(envelope['profileSha256'])
    schemas = schemas or e1.ContractSchemas()
    before = copy.deepcopy(envelope)
    context, atomic, evaluation = (envelope[k] for k in ('context', 'evidence', 'evaluation'))
    for value, name, identity, projection in (
            (context, 'evidence-context-v2', 'contextId', e1.context_identity_projection),
            (atomic, 'atomic-evidence-v2', 'evidenceId', e1.evidence_identity_projection),
            (evaluation, 'criterion-evaluation-v2', 'evaluationId', e1.evaluation_identity_projection)):
        schemas.validate(value, 'urn:varvis:schema:' + name)
        equal(value[identity], identity.removesuffix('Id') + '-sha256-' + e1.canonical_sha256(projection(value)), identity)
    variant = context['variant']
    equal(variant['alleleId'], 'allele-sha256-' + e1.canonical_sha256(e1.allele_identity_projection(context)), 'allele ID')
    equal(variant['assemblyLineage']['mappingStatus'], 'NATIVE', 'assembly lineage')
    equal(variant['normalizedAllele'], variant['evidenceQueryAllele'], 'native evidence allele')
    equal(variant['inputAllele'], variant['normalizedAllele'], 'fixed normalized public input')
    equal(context['gene']['hgncId'], 'HGNC:11621', 'gene')
    equal(context['disease']['mondoId'], 'MONDO:0015967', 'disease')
    equal(context['moi'], 'AUTOSOMAL_DOMINANT', 'MOI')
    equal(f"{context['transcript']['accession']}.{context['transcript']['version']}", model['transcript'], 'selected transcript')
    equal(context['transcript']['annotationSourceVersion'], '116.0', 'annotation version')
    require('PM2_CONTEXT' not in context['transcript']['selectionPolicy'], 'PM2-only does not authorize PVS1')
    equal(evaluation['contextId'], context['contextId'], 'evaluation context')
    equal(atomic['contextId'], context['contextId'], 'evidence context')
    equal(atomic['deduplicationKey'], 'dedup-sha256-' + e1.canonical_sha256(e1.evidence_deduplication_projection(atomic)), 'evidence deduplication')
    equal(evaluation['criterionId'], 'ACMG_AMP_2015:PVS1', 'criterion')
    equal(evaluation['evaluationResult']['status'], 'MET', 'positive result')
    equal(evaluation['appliedStrength'], 'VERY_STRONG', 'positive strength')
    equal(envelope['profileSha256'], e1.canonical_sha256(profile), 'envelope profile')
    equal(envelope['policy'], profile['policy'], 'envelope policy')
    equal(envelope['transcriptModelBinding'], profile['transcriptModel'], 'envelope model')
    equal(envelope['classificationEnabled'], False, 'no classification')
    selection = copy.deepcopy(envelope['policySelection'])
    selection_id = selection.pop('selectionId')
    equal(selection_id, 'cspec-selection-sha256-' + e1.canonical_sha256(selection), 'selection ID')
    equal(selection['authority'], profile['policy']['authority'], 'GN017 authority')
    equal(selection['contextId'], context['contextId'], 'selection context')
    equal(selection['profileSha256'], envelope['profileSha256'], 'selection profile')
    equal(selection['selectedPolicyId'], profile['policy']['policyId'], 'selected policy')
    binding = evaluation['ruleBinding']
    equal(binding['cspecSelectionId'], selection_id, 'bound selection')
    equal(binding['ruleRegistryId'], profile['registryBinding']['registryId'], 'bound registry')
    equal(binding['ruleRegistrySha256'], profile['registryBinding']['registrySha256'], 'bound registry hash')
    resource = profile['resources'][0]
    equal(atomic['source']['sourceId'], resource['resourceId'], 'VEP resource')
    equal(atomic['source']['resourceVersion'], resource['resourceVersion'], 'VEP resource version')
    equal(atomic['source']['resourceSha256'], resource['identityFingerprintSha256'], 'VEP resource identity')
    equal(atomic['queryOutcome']['status'], 'HIT', 'stored VEP evidence hit')
    equal(atomic['resourceExecution']['status'], 'SUCCEEDED', 'stored VEP source execution')
    equal(atomic['queryOutcome']['queryFingerprint'], e1.canonical_sha256({
        'resourceId': resource['resourceId'], 'queryAllele': variant['evidenceQueryAllele']}), 'VEP query allele identity')
    trace = evaluation['deterministicTrace']
    equal(trace['evaluatorVersion'], profile['profileVersion'], 'saved evaluator version')
    equal(trace['algorithmVersion'], profile['profileVersion'], 'saved algorithm version')
    equal(trace['traceSha256'], e1.canonical_sha256(e1.deterministic_trace_projection(trace, include_hash=False)), 'trace hash')
    equal(trace['inputEvidenceIds'], [atomic['evidenceId']], 'trace evidence')
    equal(evaluation['evidenceIds'], trace['inputEvidenceIds'], 'evaluation evidence')
    inputs, annotation = envelope['resolvedInput'], envelope['storedAnnotation']
    params = {row['name']: row['value'] for row in trace['parameters']}
    equal(len(params), len(trace['parameters']), 'unique trace parameters')
    equal(params['resolvedInputSha256'], e1.canonical_sha256(inputs), 'resolved input hash')
    equal(params['profileSha256'], envelope['profileSha256'], 'trace profile')
    equal(params['policySelectionSha256'], e1.canonical_sha256(envelope['policySelection']), 'trace selection')
    equal(inputs['storedAnnotationSha256'], e1.canonical_sha256(annotation), 'annotation hash')
    equal(inputs['transcriptModelSha256'], profile['transcriptModel']['canonicalSha256'], 'input model hash')
    for key, value in inputs.items():
        scalar = 'null' if value is None else str(value).lower() if isinstance(value, bool) else str(value)
        equal(params.get(key), scalar, 'trace input ' + key)
    verify_vep_measurements(atomic['measurements'], {
        'vep.selectedTranscriptId': model['transcript'], 'vep.exon': annotation['exon'],
        'consequenceTerm': annotation['consequence'], 'hgvsc': annotation['hgvsCoding'],
        'hgvsp': annotation['hgvsProtein'], 'proteinIdentity': model['protein'],
        'proteinPosition': annotation['proteinPosition'], 'aminoAcids': annotation['aminoAcids']})
    allele = variant['evidenceQueryAllele']
    expected_type = 'SNV' if len(allele['reference']) == len(allele['alternate']) == 1 else (
        'SMALL_INSERTION' if len(allele['alternate']) > len(allele['reference']) else 'SMALL_DELETION')
    equal(variant['variantType'], expected_type, 'variant type')
    derived = derive_science(allele, annotation, model, profile['policy'])
    for key, value in derived.items():
        require(key in inputs, 'missing derived field: ' + key)
        equal(inputs[key], value, 'independent sequence ' + key)
    if formal_candidate is not None:
        formal = formal_candidate
        key = formal['observation']['key']
        equal((allele['assembly'], allele['contig']), (key['genomeAssembly'], key['chromosome']), 'formal assembly')
        for name in ('position', 'reference', 'alternate'):
            equal(allele[name], key[name], 'formal ' + name)
        equal(formal['candidateIdentity']['modeOfInheritance'], 'AD', 'formal AD identity')
        matches = [t for t in formal['transcriptAnnotations'] if t.get('hgvsCoding') == annotation['hgvsCoding']]
        require(len(matches) == 1, 'formal annotation must be unique')
        original = matches[0]
        equal({k: v for k, v in annotation.items() if k != 'timestamp'},
              {k: v for k, v in original.items() if k != 'timestamp'}, 'complete original annotation')
        equal(timestamp_seconds(annotation['timestamp']), timestamp_seconds(original['timestamp']),
              'original annotation exact nanoseconds')
    equal(envelope, before, 'Java JSON nodes preserved')
    return {'allele': allele, 'variantType': expected_type, 'ptcStart': derived['ptcStart'],
            'ptcEnd': derived['ptcEnd'], 'distanceToLastJunction': derived['distanceToLastJunction'],
            'status': 'PASS'}


def verify(directory=None):
    result = {'profileAndRegistry': 'PASS', 'otherCriteriaUnchanged': True, 'pvs1Envelopes': 0}
    if directory is None:
        profile, _ = verify_profile()
        result.update(profileVersion=profile['profileVersion'], profileSha256=e1.canonical_sha256(profile))
        return result
    directory = Path(directory)
    raw = (directory / 'first-post-response.json').read_bytes()
    saved = json.loads(raw)
    saved_profile_hashes = {attempt['pvs1']['profileSha256'] for candidate in saved['candidates']
                           for attempt in candidate['attempts'] if 'evaluation' in attempt.get('pvs1', {})}
    equal(len(saved_profile_hashes), 1, 'one original saved PVS1 profile identity')
    profile, model = verify_profile(saved_profile_hashes.pop())
    verify_execution_profile(saved['executionProfile'], profile)
    equal((directory / 'saved-business.json').read_bytes(), raw, 'saved original HTTP bytes')
    for label in ('saved-get', 'repeat-post', 'rebuilt-saved-get', 'rebuilt-repeat-post'):
        equal((directory / (label + '-response.json')).read_bytes(), raw, label + ' complete response bytes')
        event = read(directory / (label + '-event.json'))
        equal(event['runId'], saved['runId'], label + ' run ID')
        equal(event['responseSha256'], sha(raw), label + ' response hash')
        equal(event['status'], 200, label + ' HTTP status')
        equal(event['transport'], 'REAL_NETWORK_HTTP', label + ' HTTP transport')
        equal(event['method'], 'POST' if 'post' in label else 'GET', label + ' method')
        require(bool(event['callIncrement']) and all(value == 0 for value in event['callIncrement'].values()), label + ' calls increased')
    state, reuse = read(directory / 'network-state.json'), read(directory / 'reuse-report.json')
    require(reuse['instance']['processId'] != state['processIdBeforeRestart'], 'no separate JVM restart evidence')
    equal(reuse['jvmRestartCovered'], True, 'restart covered')
    equal(reuse['instance']['instanceId'], state['instanceId'], 'same rebuilt task instance')
    equal(reuse['instance']['sourceHead'], state['sourceHead'], 'same rebuilt source')
    equal(reuse['instance']['configurationSha256'], state['configurationSha256'], 'same rebuilt configuration')
    equal(reuse['instance']['executionProfile'], state['executionProfile'], 'same rebuilt execution profile')
    formal_rows = read(directory / 'formal-topn.json', decimals=True)
    formal = {row['candidateId']: row for row in formal_rows}
    equal(len(formal), len(formal_rows), 'unique formal candidate IDs')
    options = read(directory / 'input-options.json')
    equal(saved['candidateIds'], [row['candidateId'] for row in options['candidates']], 'formal candidate order')
    schemas, positives = e1.ContractSchemas(), []
    for candidate in saved['candidates']:
        require(candidate['candidateId'] in formal, 'evaluation not in original formal candidates')
        for attempt in candidate['attempts']:
            envelope = attempt.get('pvs1', {})
            if 'evaluation' in envelope:
                verified = verify_envelope(envelope, formal[candidate['candidateId']], profile=profile, model=model, schemas=schemas)
                verified['candidateId'] = candidate['candidateId']
                positives.append(verified)
    observed = {(p['allele']['position'], p['allele']['reference'], p['allele']['alternate']) for p in positives}
    equal(observed, CONTROLS, 'both historical SNVs and both frameshifts')
    equal(len(positives), len(CONTROLS), 'exactly four positive envelopes')
    result.update(pvs1Envelopes=len(positives), distinctRealPositives=len(observed), sequenceChecks=positives,
                  javaNodesUnchanged=True, readbackRepeatIdentical=True, jvmRestartCovered=True, runId=saved['runId'],
                  profileVersion=profile['profileVersion'], profileSha256=e1.canonical_sha256(profile))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', nargs='?', type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.directory)))
