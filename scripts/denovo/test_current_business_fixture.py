"""CURRENT-bound synthetic originals, replayed by the unmodified owned chain.

The input is a Java-selected target, not a scientific policy.  Every reference,
transcript, observation and policy here is independently generated SYNTHETIC
test material before registration.  No provider, VEP or live model is called.
"""
from copy import deepcopy
import gzip
from pathlib import Path
import re
import shutil
import tempfile
import unittest
from unittest.mock import patch
from xml.sax.saxutils import escape

from bundle_candidate_intake import replay_intake
from bundle_model_contract import RESPONSE_VERSION
from core_local_identity import CODONS
from model_literature_contract import canonical, digest, parse
from test_bundle_qualification import article, PMCID
from test_core_context_review import LocalFixture, policy as base_policy
import test_core_source_event_discovery as discovery_fixture
from test_round4_adoption_fixture import (KNOWLEDGE, SCENARIOS as ROUND4_SCENARIOS,
    reported, source_table as original_source_table)


SCENARIOS = ROUND4_SCENARIOS | {'TISSUE_UNKNOWN', 'TISSUE_WRONG_TARGET'}
MAX_REFERENCE_BASES = 1_000_000
TRANSCRIPT = 'NM_000001.1'


def target_binding(path):
    raw = Path(path).read_bytes()
    if len(raw) > 16384:
        raise ValueError('CURRENT_TARGET_SIZE_LIMIT')
    value = parse(raw)
    if (type(value) is not dict or set(value) != {'allele', 'hgncId', 'geneSymbol', 'diseaseId', 'inheritance'}
            or not all(type(value[key]) is str for key in ('hgncId', 'geneSymbol', 'diseaseId', 'inheritance'))
            or value['inheritance'] != 'AD'
            or not re.fullmatch(r'HGNC:[1-9][0-9]*', value['hgncId'])
            or not re.fullmatch(r'[A-Za-z][A-Za-z0-9.-]{0,39}', value['geneSymbol'])
            or not re.fullmatch(r'MONDO:[0-9]{7}', value['diseaseId'])):
        raise ValueError('CURRENT_TARGET_BINDING_INVALID')
    allele = value['allele']
    if (type(allele) is not dict or set(allele) != {'assembly', 'contig', 'position', 'reference', 'alternate'}
            or allele['assembly'] != 'GRCh38' or type(allele['contig']) is not str
            or not re.fullmatch(r'(?:[1-9]|1[0-9]|2[0-2])', allele['contig'])
            or type(allele['position']) is not int or not 1 <= allele['position'] <= MAX_REFERENCE_BASES - 2
            or allele['reference'] not in ('A', 'C', 'G', 'T') or allele['alternate'] not in ('A', 'C', 'G', 'T')
            or allele['reference'] == allele['alternate']):
        raise ValueError('CURRENT_SYNTHETIC_REFERENCE_REQUIRES_BOUNDED_GRCH38_AUTOSOMAL_SNV')
    return value, digest(raw)


def identity_options(root, target, target_sha, scenario):
    """Seal real tiny reference/index/receipt and explicit invented transcript."""
    f = LocalFixture(root); allele = target['allele']; position = allele['position']
    coding_position = 1 if position == 1 else 2
    start = position - coding_position + 1
    codon = list('AAC'); codon[coding_position - 1] = allele['reference']
    changed = list(codon); changed[coding_position - 1] = allele['alternate']
    protein = CODONS[''.join(codon)] + '1' + CODONS[''.join(changed)]
    coding = 'c.' + str(coding_position) + allele['reference'] + '>' + allele['alternate']
    sequence = bytearray(b'A' * max(300, position + 2))
    sequence[start - 1:start + 2] = ''.join(codon).encode()
    header = ('>chr' + allele['contig'] + '\n').encode()
    raw = header + b'\n'.join(bytes(sequence[i:i + 50]) for i in range(0, len(sequence), 50)) + b'\n'
    fasta = f.put('reference.fa', raw)
    index = f.put('reference.fa.fai', ('chr' + allele['contig'] + '\t' + str(len(sequence)) + '\t'
        + str(len(header)) + '\t50\t51\n').encode())
    receipt = parse((f.local / 'receipt.json').read_bytes())
    record = receipt['records']['reference']; record.update(expectedSha256=digest(raw), observedSha256=digest(raw))
    record['actualChecksumReceipt'].update(expectedSha256=digest(raw), observedSha256=digest(raw),
        byteLength=len(raw), mtimeNs=(f.local / 'reference.fa').stat().st_mtime_ns, indexSha256=index['sha256'])
    f.config['reference'].update(receipt=f.put('receipt.json', canonical(receipt)), sha256=fasta['sha256'],
        aliases=f.put('aliases.txt', (allele['contig'] + ' chr' + allele['contig'] + '\n').encode()))
    gtf = ('# SYNTHETIC ONLY; no biological transcript claim\nchr' + allele['contig'] + '\tSYNTHETIC\tCDS\t'
        + str(start) + '\t' + str(start + 2) + '\t.\t+\t0\tgene_id "' + target['hgncId']
        + '"; gene_name "' + target['geneSymbol'] + '"; transcript_id "ENST000001.1";\n').encode()
    gtf_pin = f.put('synthetic.gtf', gtf)
    f.annotation.update(hgncId=target['hgncId'], geneSymbol=target['geneSymbol'], contig=allele['contig'],
        rawGtfSha256=gtf_pin['sha256'], exons=[{'cdsStart': 1, 'cdsEnd': 3,
            'genomicStart': start, 'genomicEnd': start + 2}])
    f.seal_annotation()
    gencode = parse((f.local / 'gencode-manifest.json').read_bytes())
    gencode['source']['sha256'] = gtf_pin['sha256']
    f.config['gencodeManifest'] = f.put('gencode-manifest.json', canonical(gencode))
    mane = ('RefSeq_nuc\tsymbol\tHGNC_ID\tEnsembl_nuc\tEnsembl_prot\tchr_strand\n' + TRANSCRIPT
        + '\t' + target['geneSymbol'] + '\t' + target['hgncId'] + '\tENST000001.1\tENSP000001.1\t+\n').encode()
    f.config['mane'] = f.put('mane.gz', gzip.compress(mane, mtime=0))
    f.config['contextManifest'] = f.put('context-manifest.json', canonical({'resources': {
        'mane': {'rawSha256': f.config['mane']['sha256']}}, 'materialClass': 'SYNTHETIC'}))
    f.put('synthetic-target-metadata.json', canonical({'materialClass': 'SYNTHETIC', 'target': target,
        'targetInputSha256': target_sha, 'referenceSha256': fasta['sha256'], 'gtfSha256': gtf_pin['sha256'],
        'description': 'Invented transcript, reference and knowledge for CURRENT binding tests; no clinical claim.'}))
    policy = base_policy(); policy['scope'] = {k: target[k] for k in ('hgncId', 'diseaseId', 'inheritance')}
    policy['policyId'] = 'SYNTHETIC-CURRENT-' + ('GENERAL' if scenario == 'GENERAL_ASSUMED' else
        'COMBINED' if scenario.startswith('COMBINED') else 'TISSUE')
    policy['expertPolicyStatus'] = 'GENERAL_ALLOWED' if scenario == 'GENERAL_ASSUMED' else 'APPLICABLE_IMPLEMENTED'
    policy['conditions'][0]['category'] = 'CONSISTENT'
    policy['literatureTerms'][0]['aliases'] = ['Synthetic trait']
    policy['specialEvents'] = {'mode': 'SVI_1_1' if scenario == 'GENERAL_ASSUMED' else
        'SVI_1_1_COMBINED_PS2' if scenario.startswith('COMBINED') else 'TISSUE_DIFFERENTIAL_PS2', 'sourceIds': ['paper']}
    policy['sources'][0].update(sha256=digest(KNOWLEDGE), locator='synthetic knowledge paragraph 1',
        quote='Synthetic trait is consistent.')
    config = {'schemaVersion': 'denovo-core-context-config-v1', 'identity': f.config, 'contexts': [{
        'scope': deepcopy(policy['scope']), 'policy': f.put('policy.json', canonical(policy)),
        'sources': {'paper': f.put('knowledge.txt', KNOWLEDGE)}}]}
    options = {'root': str(root), **f.put('core-config.json', canonical(config))}
    return options, policy, coding, protein


def proposed_candidate(source, target, coding, protein):
    """The registered_intake helper's mock-model response, never qualification."""
    units = source['main']['units']
    own = next(u for u in units if u['kind'] == 'BODY_PARAGRAPH' and coding in u['text'])
    method = next(u for u in units if u['text'] == 'Patients were recruited for this study.')
    citation = lambda unit: {'unitId': unit['unitId'], 'quote': unit['text']}
    return {'schemaVersion': RESPONSE_VERSION, 'coverage': 'EXTRACTED_WITHIN_TARGET',
        'sourceAssessments': [{'materialKind': 'INDIVIDUAL_CASE_MATERIAL', 'citation': citation(own)}],
        'cases': [{'caseLabel': 'A', 'caseOrigin': 'AUTHOR_COHORT',
            'caseCitation': {'unitId': own['unitId'], 'quote': 'Case A was studied.'},
            'originCitations': [citation(method)], 'fields': [
                {'field': key, 'sourceValue': value, 'subject': 'PROBAND', 'citation': citation(own)}
                for key, value in [('geneSymbol', target['geneSymbol']), ('variantText', coding),
                    ('proteinChange', 'p.' + protein), ('transcript', TRANSCRIPT)]]}]}


def build(root, target, target_sha, kind='JSON', scenario='TISSUE_BOTH'):
    if kind not in {'JSON', 'JATS'} or scenario not in SCENARIOS:
        raise ValueError('CURRENT_FIXTURE_KIND_OR_SCENARIO_UNSUPPORTED')
    # This table format reuses the explicit event person for trio.personId;
    # it cannot encode a contradictory, independently reported trio subject.
    if kind == 'JATS' and scenario == 'TISSUE_CROSS_TRIO':
        raise ValueError('CURRENT_JATS_INDEPENDENT_TRIO_SUBJECT_UNSUPPORTED')
    options, policy, coding, protein = identity_options(root, target, target_sha, scenario)
    value = reported('TISSUE_BOTH' if scenario in {'TISSUE_UNKNOWN', 'TISSUE_WRONG_TARGET'} else scenario)
    if scenario == 'TISSUE_UNKNOWN':
        value['trio']['subjectTarget'] = 'UNKNOWN'
    raw = article().decode().replace('EXAMPLE', target['geneSymbol'])
    raw = raw.replace('c.123A&gt;G', escape(coding)).replace('123A&gt;G', escape(coding))
    raw = raw.replace('p.T41A', 'p.' + protein).replace('T41A', protein)
    raw = raw.replace('<tr><td>Protein</td>', '<tr><td>Transcript</td><td>' + TRANSCRIPT + '</td></tr><tr><td>Protein</td>')
    allele = target['allele']
    genomic = allele['assembly'] + ' chr' + allele['contig'] + ':' + str(allele['position']) + ' ' + allele['reference'] + '>' + allele['alternate']
    raw = raw.replace('was observed.', 'was observed. Transcript ' + TRANSCRIPT + '. Original genomic representation ' + escape(genomic) + '.')
    if scenario.startswith('COMBINED') or scenario == 'GENERAL_ASSUMED':
        raw = raw.replace('SNP analysis confirmed both maternity and paternity.', 'No parentage testing was performed.')
    if scenario.startswith('TISSUE_C2'):
        raw = raw.replace('Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.',
            'Sanger sequencing confirmed the heterozygous variant in the proband.')
        raw = raw.replace('<fig><caption><p>(A) Other panel. (B) Sanger sequencing of the patient and parents to show that the heterozygous mutation is de novo.</p></caption></fig>', '')
        raw = raw.replace('SNP analysis confirmed both maternity and paternity.', 'Parental samples were unavailable.')
    if scenario == 'TISSUE_ORDINARY_PARENT_PRESENT':
        raw = raw.replace('Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.',
            'Sanger sequencing confirmed the heterozygous variant in the proband. The variant was inherited from the mother.')
    if scenario == 'TISSUE_UNKNOWN':
        raw = raw.replace('Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.',
            'Target-specific testing results were not reported.')
        raw = raw.replace('<fig><caption><p>(A) Other panel. (B) Sanger sequencing of the patient and parents to show that the heterozygous mutation is de novo.</p></caption></fig>', '')
    reported_allele = deepcopy(allele)
    if scenario == 'TISSUE_WRONG_TARGET':
        reported_allele['position'] += 1
    attachment = None
    if kind == 'JSON':
        source_event = deepcopy(value); source_event.pop('schemaVersion')
        row = {'caseLabel': 'A', 'geneSymbol': target['geneSymbol'], 'allele': reported_allele, 'specialEvent': source_event}
        rows = [row, deepcopy(row)] if scenario == 'TISSUE_DUPLICATE_RECORD' else [row]
        attachment = canonical({'materialClass': 'SYNTHETIC', 'cases': rows})
    else:
        source_table = original_source_table(value).decode().replace('EXAMPLE', target['geneSymbol'])
        source_table = source_table.replace(escape(canonical(discovery_fixture.TARGET).decode()), escape(canonical(reported_allele).decode()))
        extra_rows = source_table.split('</tr>', 1)[1].split('</table>', 1)[0]
        if scenario == 'TISSUE_DUPLICATE_RECORD':
            raise ValueError('CURRENT_DUPLICATE_RECORD_REQUIRES_JSON')
        raw = raw.replace('</table>', extra_rows + '</table>', 1)
    raw = raw.encode()
    # Only the existing helper's synthetic model proposal is supplied here.
    # Source parsing, qualify/review, LocalIdentity, proofs and replay are real.
    with patch.object(discovery_fixture, 'candidate', lambda source: proposed_candidate(source, target, coding, protein)):
        source, intake = discovery_fixture.registered_intake(root, raw, attachment)
    facts = replay_intake(raw, PMCID, digest(raw), intake, work_root=root, core_context=options)
    return facts['cases'][0]['coreEvaluation'], facts, source, intake, options, policy


def write_java_fixture(output, target_json_path, kind='JSON', scenario='TISSUE_BOTH'):
    """Return java-round4-fixture.json; all originals predate actual replay."""
    target, target_sha = target_binding(target_json_path)
    output = Path(output)
    if output.exists():
        raise ValueError('NEW_FIXTURE_DIRECTORY_REQUIRED')
    temporary = Path(__file__).resolve().parents[2] / 'local/temp'; temporary.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='cb-', dir=temporary) as directory:
        root = Path(directory)
        core, facts, source, intake, options, policy = build(root, target, target_sha, kind, scenario)
        output.mkdir(parents=True)
        shutil.copytree(root / 'local', output / 'local')
        artifacts = {'synthetic-literature.xml': source['raw'], 'bundle-intake.json': canonical(intake),
            'facts.json': canonical(facts), 'target-binding.json': Path(target_json_path).read_bytes()}
        for name, raw in artifacts.items():
            (output / name).write_bytes(raw)
        model_index = {'schemaVersion': 'denovo-bundle-intake-index-v1', 'artifacts': [{
            'pmcid': source['pmcid'], 'sourceSha256': digest(source['raw']), 'path': 'bundle-intake.json',
            'sha256': digest(artifacts['bundle-intake.json'])}]}
        (output / 'model-artifacts.json').write_bytes(canonical(model_index))
        support = output / 'scripts/acmg-generalization'; support.mkdir(parents=True)
        for name in ('normalize_public_panel.py', 'build_transcript_model.py'):
            (support / name).write_bytes((Path(__file__).resolve().parents[1] / 'acmg-generalization' / name).read_bytes())
        result = {'core': core, 'policy': policy, 'policySources': {'paper': KNOWLEDGE.decode()},
            'pmcid': source['pmcid'], 'sourceSha256': digest(source['raw']), 'bundleSha256': source['bundleSha256'],
            'coreContext': {'root': str(output.resolve()), 'path': options['path'], 'sha256': options['sha256']},
            'sourceClass': 'SYNTHETIC_NORMAL_REGISTERED_' + kind, 'criterionDecision': 'NOT_RUN',
            'scenario': scenario, 'target': target, 'targetInputSha256': target_sha,
            'fixtureManifest': {'schemaVersion': 'denovo-current-business-fixture-v1', 'materialClass': 'SYNTHETIC',
                'identityMethod': 'ACTUAL_LOCAL_IDENTITY_EXPLICIT_SYNTHETIC_MANE_GENCODE_REFERENCE',
                'providers': 'NOT_USED', 'modelTransport': 'SYNTHETIC_TEST',
                'originals': {name: digest(raw) for name, raw in artifacts.items()}}}
        path = output / 'java-round4-fixture.json'; path.write_bytes(canonical(result)); return path


class CurrentBusinessFixtureTests(unittest.TestCase):
    def setUp(self):
        temporary = Path(__file__).resolve().parents[2] / 'local/temp'; temporary.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='cbt-', dir=temporary); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.target = {'allele': {'assembly': 'GRCh38', 'contig': '11', 'position': 2, 'reference': 'T', 'alternate': 'A'},
            'hgncId': 'HGNC:1748', 'geneSymbol': 'CDH1', 'diseaseId': 'MONDO:0007648', 'inheritance': 'AD'}

    def test_json_and_jats_bind_current_before_real_identity_replay(self):
        for index, (kind, scenario) in enumerate([('JSON', 'TISSUE_BOTH'), ('JATS', 'TISSUE_C1'), ('JSON', 'TISSUE_C2'),
                ('JATS', 'COMBINED_ASSUMED'), ('JSON', 'GENERAL_ASSUMED')]):
            with self.subTest(kind=kind, scenario=scenario):
                folder = self.root / str(index); folder.mkdir()
                core, _, source, _, _, policy = build(folder, self.target, digest(canonical(self.target)), kind, scenario)
                self.assertEqual('READY_FOR_EVALUATION', core['status'], core['blockers'])
                self.assertEqual(self.target['allele'], core['values']['/groups/variant/resolvedAllele'])
                self.assertEqual(self.target['hgncId'], core['values']['/context/hgncId'])
                self.assertEqual(self.target['geneSymbol'], core['values']['/groups/gene/symbol'])
                self.assertEqual(self.target['diseaseId'], policy['scope']['diseaseId'])
                self.assertIn(b'GRCh38 chr11:2 T&gt;A', source['raw'])
                self.assertEqual('NOT_RUN', core['criterionDecision'])
                if scenario.endswith('ASSUMED'):
                    self.assertEqual('ASSUMED_NOT_TESTED', core['specialEvent']['trio']['paternity'])

    def test_original_wrong_target_unknown_and_quality_are_not_repaired(self):
        for index, scenario in enumerate(('TISSUE_WRONG_TARGET', 'TISSUE_UNKNOWN', 'TISSUE_QUALITY', 'TISSUE_CROSS_TRIO',
                'COMBINED_EXCLUDED', 'TISSUE_ORDINARY_PARENT_PRESENT', 'TISSUE_C2_NO_DIFFERENTIAL')):
            with self.subTest(scenario=scenario):
                folder = self.root / str(index); folder.mkdir()
                core, *_ = build(folder, self.target, digest(canonical(self.target)), 'JSON', scenario)
                self.assertEqual('NOT_RUN', core['criterionDecision'])
                if scenario == 'TISSUE_WRONG_TARGET':
                    self.assertNotIn('specialEvent', core)
                    self.assertNotEqual('READY_FOR_EVALUATION', core['status'])
                elif scenario == 'TISSUE_UNKNOWN':
                    self.assertEqual('UNKNOWN', core['specialEvent']['trio']['subjectTarget'])
                elif scenario == 'TISSUE_QUALITY':
                    self.assertEqual('FAILED', core['specialEvent']['trio']['quality'])
                elif scenario == 'TISSUE_CROSS_TRIO':
                    self.assertEqual('SYNTHETIC-different-person', core['specialEvent']['trio']['personId'])
                elif scenario == 'COMBINED_EXCLUDED':
                    self.assertEqual('EXCLUDED', core['specialEvent']['trio']['paternity'])
        with self.subTest(kind='JATS', scenario='TISSUE_CROSS_TRIO'):
            folder = self.root / 'j0'; folder.mkdir()
            with self.assertRaisesRegex(ValueError, '^CURRENT_JATS_INDEPENDENT_TRIO_SUBJECT_UNSUPPORTED$'):
                build(folder, self.target, digest(canonical(self.target)), 'JATS', 'TISSUE_CROSS_TRIO')
            self.assertEqual([], list(folder.iterdir()))
        for index, (scenario, path, expected) in enumerate([
                ('TISSUE_UNKNOWN', '/trio/subjectTarget', 'UNKNOWN'),
                ('TISSUE_QUALITY', '/trio/quality', 'FAILED')]):
            with self.subTest(kind='JATS', scenario=scenario):
                folder = self.root / ('j' + str(index + 1)); folder.mkdir()
                core, *_ = build(folder, self.target, digest(canonical(self.target)), 'JATS', scenario)
                self.assertEqual(expected, core['specialEvent']['trio'][path.rsplit('/', 1)[1]])
                fields = [field for record in core['sourceDiscovery']['records'] for field in record['fields']]
                self.assertTrue(any(field['path'] == path and field['quote'] == expected
                    and field['kind'] == 'JATS_UNIT' for field in fields), fields)

    def test_export_has_originals_config_and_strict_target_boundary(self):
        independent = deepcopy(self.target)
        independent.update(hgncId='HGNC:999999996', geneSymbol='SYNTHETIC2', diseaseId='MONDO:0000002')
        independent['allele'].update(contig='1', position=101, reference='G', alternate='C')
        target = self.root / 'current.json'; target.write_bytes(canonical(independent))
        path = write_java_fixture(self.root / 'export', target)
        result = parse(path.read_bytes())
        self.assertEqual('java-round4-fixture.json', path.name)
        self.assertEqual(digest(target.read_bytes()), result['targetInputSha256'])
        self.assertEqual('SYNTHETIC', result['fixtureManifest']['materialClass'])
        for name, pin in result['fixtureManifest']['originals'].items():
            self.assertEqual(pin, digest((path.parent / name).read_bytes()))
        config = result['coreContext']; self.assertEqual(config['sha256'], digest((path.parent / config['path']).read_bytes()))
        raw = (path.parent / 'synthetic-literature.xml').read_bytes()
        replay = replay_intake(raw, result['pmcid'], result['sourceSha256'],
            parse((path.parent / 'bundle-intake.json').read_bytes()), work_root=path.parent, core_context=config)
        again = replay['cases'][0]['coreEvaluation']
        self.assertEqual('READY_FOR_EVALUATION', again['status'], again['blockers'])
        self.assertEqual(independent['allele'], again['values']['/groups/variant/resolvedAllele'])
        self.assertEqual(independent['hgncId'], again['values']['/context/hgncId'])
        bad = deepcopy(self.target); bad['allele']['position'] = True; target.write_bytes(canonical(bad))
        with self.assertRaisesRegex(ValueError, 'BOUNDED_GRCH38_AUTOSOMAL_SNV'):
            target_binding(target)


if __name__ == '__main__':
    unittest.main()
