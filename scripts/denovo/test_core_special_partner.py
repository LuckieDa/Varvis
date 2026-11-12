"""A7 actual raw-VCV parser reuse, with synthetic originals and no live calls."""
from copy import deepcopy
from pathlib import Path
import unittest

from model_literature_contract import canonical, digest, parse
from core_special_partner import project
import test_core_special_event as event_fixtures
from test_pm3_clinvar_supply import RAW, ALLELE as PARTNER

RAW_EXAMPLE = RAW.replace(b'Symbol="TEST"', b'Symbol="EXAMPLE"')


def configure(fixture, raw=RAW_EXAMPLE, status='INDEPENDENT_P_LP'):
    fixture.p['scope']['diseaseId'] = 'MONDO:0000001'
    fixture.envelope['scope'] = deepcopy(fixture.p['scope'])
    fixture.envelope['specialEvent']['partner'].update(status=status, allele=PARTNER, phase='TRANS',
        sourceRefs=[digest(raw) + '#/VariationArchive/ClassifiedRecord/ClinicalAssertionList'])
    spec = {**fixture.f.put('partner.xml', raw), 'accession': 'VCV000000123'}
    return {'clinvar': [spec]}


class PartnerTests(unittest.TestCase):
    def setUp(self):
        self.fixture = event_fixtures.SpecialEventTests()
        self.fixture.setUp(); self.addCleanup(self.fixture.doCleanups)

    def build(self, raw=RAW_EXAMPLE, **kwargs):
        config = configure(self.fixture, raw)
        return self.fixture.build(partner_sources=config, **kwargs)

    def test_actual_xml_parser_and_independence_audit_qualify_intrinsic_list(self):
        core, doc, _, index, _ = self.build()
        wrapper = core['partnerQualification']; qualification = wrapper['qualification']
        self.assertEqual('VERIFIED', wrapper['status'], wrapper)
        self.assertEqual('VERIFIED', qualification['independentClassification'])
        self.assertEqual('PATHOGENIC', qualification['classification'])
        self.assertEqual(['SCV000001.1'], qualification['qualifiedScvs'])
        self.assertEqual('EXACT_SOURCE_ASSERTIONS_AVAILABLE', wrapper['sourceRead']['status'])
        self.assertEqual('INDEPENDENT_P_LP', core['specialEvent']['partner']['status'])
        self.assertEqual(event_fixtures.ALLELE, wrapper['targetAllele'])
        self.assertEqual(PARTNER, wrapper['partnerAllele'])
        self.assertEqual('MONDO:0000001', wrapper['scope']['diseaseId'])
        proof = core['partnerQualificationProof']
        self.assertEqual(wrapper, parse(proof['canonicalUtf8'].encode()))
        self.assertEqual(proof['sha256'], digest(proof['canonicalUtf8'].encode()))
        self.assertEqual(digest(RAW_EXAMPLE) + '#/', proof['sourceRefs'][0]['sourceRef'])
        self.assertEqual('XML', doc['sources']['special-partner-vcv-0']['format'])
        self.assertEqual(RAW_EXAMPLE, (self.fixture.root / 'output' / doc['sources']['special-partner-vcv-0']['path']).read_bytes())
        for name in ('pm3_partner_sources.py', 'pm3_partner_qualification.py', 'pm3_partner_dependencies.py',
                '../ps1_pm5/prepare_reference_evidence.py', '../ps1_pm5/prepare_expert_reference_evidence.py'):
            self.assertIn(name, wrapper['implementation'])
            self.assertIn(name, __import__('core_evaluation_intake').FILES)

    def test_json_independent_label_without_configured_original_is_unresolved(self):
        configure(self.fixture)
        core, *_ = self.fixture.build()
        self.assertEqual('INDEPENDENT_P_LP', core['specialEvent']['partner']['status'])
        self.assertEqual('UNRESOLVED', core['partnerQualification']['status'])
        self.assertEqual('NOT_CONFIGURED', core['partnerQualification']['sourceRead']['status'])

    def test_pathogenic_label_without_complete_criteria_does_not_qualify(self):
        raw = RAW_EXAMPLE.replace(b'<Comment>ACMG/AMP criteria applied: PS3, PM2, PP3.</Comment>', b'')
        core, *_ = self.build(raw)
        result = core['partnerQualification']
        self.assertEqual('EXACT_SOURCE_ASSERTIONS_AVAILABLE', result['sourceRead']['status'])
        self.assertEqual('Pathogenic', result['sourceRead']['assertions'][0]['sourceClassification'])
        self.assertEqual('UNRESOLVED', result['status'])
        self.assertIn('PM3_PARTNER_COMPLETE_CRITERIA_LIST_MISSING', result['qualification']['issues'])

    def test_comparison_list_cannot_be_assumed_target_independent(self):
        core, *_ = self.build(RAW_EXAMPLE.replace(b'PS3, PM2, PP3.', b'PS3, PM3, PP3.'))
        result = core['partnerQualification']
        self.assertEqual('UNRESOLVED', result['status'])
        self.assertIn('PM3_PARTNER_COMPARISON_DEPENDENCY_UNRESOLVED', result['qualification']['issues'])
        self.assertTrue(result['qualification']['assertionAssessments'][0]['criterionDependencies'])

    def test_wrong_disease_is_not_substituted(self):
        core, *_ = self.build(RAW_EXAMPLE.replace(b'ID="0000001"', b'ID="0000002"'))
        result = core['partnerQualification']
        self.assertEqual('UNRESOLVED', result['status'])
        self.assertIn('PM3_PARTNER_EXACT_DISEASE_ASSERTION_MISSING', result['qualification']['issues'])

    def test_target_itself_is_never_its_own_partner(self):
        raw = RAW_EXAMPLE.replace(b'positionVCF="101" referenceAlleleVCF="G" alternateAlleleVCF="A"',
            b'positionVCF="100" referenceAlleleVCF="A" alternateAlleleVCF="G"')
        config = configure(self.fixture, raw)
        self.fixture.envelope['specialEvent']['partner']['allele'] = event_fixtures.ALLELE
        core, *_ = self.fixture.build(partner_sources=config)
        result = core['partnerQualification']
        self.assertEqual('UNRESOLVED', result['status'])
        self.assertIn('DENOVO_PARTNER_EQUALS_TARGET', result['issues'])

    def test_wrong_allele_does_not_borrow_raw_record(self):
        core, *_ = self.build(RAW_EXAMPLE.replace(b'positionVCF="101"', b'positionVCF="102"'))
        result = core['partnerQualification']
        self.assertEqual('UNRESOLVED', result['status'])
        self.assertEqual('NOT_FOUND_IN_CONFIGURED_RECORDS', result['sourceRead']['status'])
        self.assertEqual([], result['sourceRecords'])

    def test_resigned_qualification_is_recomputed_from_original_xml(self):
        core, doc, *_ = self.build()
        wrapper = deepcopy(core['partnerQualification'])
        wrapper['qualification']['classification'] = 'LIKELY_PATHOGENIC'
        metadata = doc['sources']['special-partner-qualification']
        raw = canonical(wrapper); (self.fixture.root / 'output' / metadata['path']).write_bytes(raw)
        metadata['sha256'] = digest(raw)
        with self.assertRaisesRegex(ValueError, 'ORIGINAL_QUALIFICATION_REPLAY_MISMATCH'):
            project(self.fixture.root / 'output', doc)


def write_java_fixture(output, registry=False, pmcid=None):
    """Persist a newly generated synthetic Python replay for Java contract tests."""
    fixture = event_fixtures.SpecialEventTests(); fixture.setUp()
    try:
        config = configure(fixture)
        core, _, _, index, _ = fixture.build(partner_sources=config, derived=registry, source_pmcid=pmcid)
        output = Path(output)
        if output.exists():
            raise ValueError('NEW_FIXTURE_DIRECTORY_REQUIRED')
        output.mkdir(parents=True)
        from core_evaluation_intake import archive_package
        (output / 'source-audit-archive.json').write_bytes(canonical(archive_package(fixture.root / 'output', index)))
        (output / 'core.json').write_bytes(canonical(core))
        path = output / 'java-special-partner-fixture.json'
        path.write_bytes(canonical({'core': core, 'policy': fixture.p, 'policySources': {'paper': 'fixed knowledge'}}))
        return path
    finally:
        fixture.doCleanups()


if __name__ == '__main__':
    unittest.main()
