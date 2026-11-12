"""Synthetic pedigrees and published-rule shape regressions for PP1 transmission counts."""
from copy import deepcopy
import unittest

from pp1_meiosis import AFFECTED_NOTE, METHOD, THRESHOLD_PREFIX, FAMILY_THRESHOLD_PREFIX, family_relationships
from pp1_specification_context import select_specifications
from test_pp1_segregation import add, fixture, ledger
from test_pp1_specification_context import chosen, specification


def meiosis_spec():
    spec = specification()
    ld = spec['original']['ld']
    ld['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredModeOfInheritance'] = 'Autosomal dominant inheritance'
    clause = ld['CriteriaCode'][0]['entContent']
    clause.update(applicability='Applicable', instructionsToUse=AFFECTED_NOTE)
    for row, text in zip(clause['strengthDescriptor'], ('2', '3-4', '≥5')):
        row.update(applicability='Applicable', status='approved', text=f'{THRESHOLD_PREFIX}{text} informative meiosis')
    for row in clause['strengthDescriptor'][3:]:
        row['applicability'] = 'Not applicable'
    return spec


def context(spec=None):
    return select_specifications([spec or meiosis_spec()], {}, chosen())


def multi_family_spec():
    spec = meiosis_spec()
    clause = spec['original']['ld']['CriteriaCode'][0]['entContent']
    summary = ('PP1: Requires 3 or 4 meioses in order to apply. '
               'PP1_Strong: At least 7 meioses required across at least two families. '
               'PP1_Moderate: Requires 5 or 6 meioses in order to apply.')
    clause['instructionsToUse'] = '**GENE1 EP Specification:** ' + summary.replace('_', r'\_')
    clause['additionalComments'] = 'GENE1 EP Specification: ' + summary
    for row, text in zip(clause['strengthDescriptor'], ('3 or 4 meioses observed.', '5 or 6 meioses observed.',
                                                       '≥7 meioses observed across at least two families.')):
        row['text'] = FAMILY_THRESHOLD_PREFIX + text
    return spec


def pedigree(count, family='F1', *, explicit=True):
    q = fixture(family=family)
    q['target'] = chosen()
    q['identity']['transcript'] = 'NM_000001.7'
    prefix = '' if family == 'F1' else family + ':'
    parent = prefix + 'P'
    q['families'][family]['literalFamilyLabel'] = family
    add(q, parent, 'genotype', 'HETEROZYGOUS', family)
    add(q, parent, 'diagnosis', 'TEST disease', family)
    add(q, parent, 'proband', True, family)
    for index in range(count):
        child = f'{prefix}C{index}'
        add(q, child, 'genotype', 'HETEROZYGOUS', family)
        add(q, child, 'diagnosis', 'TEST disease', family)
        add(q, parent, 'parentOf', child, family)
        if explicit:
            add(q, child, 'alleleOriginParent', parent, family)
    return q


def article(first, *additional, declarations=(), reviewed=None):
    combined = deepcopy(first)
    for q in additional:
        combined['families'].update(q['families'])
        combined['observations'].extend(q['observations'])
    main = 'c' * 64
    units = [{'unitId': str(i), 'text': text, 'sourceSha256': main} for i, text in enumerate(declarations)]
    source = {**combined['sourceIdentity'], 'main': {'sourceSha256': main}, 'units': units}
    combined['familyRelationshipEvidence'] = family_relationships(source, set(reviewed if reviewed is not None else (u['unitId'] for u in units)),
                                                                  combined['families'])
    return combined


def evaluate(q, policy=None, pp4=0, *, exclusive=()):
    from pp1_segregation import evaluate as score
    log = ledger(pp4=pp4)
    log['scope']['diseaseId'] = chosen()['diseaseId']
    log['exclusiveFactIds'] = list(exclusive)
    return score(q if isinstance(q, list) else [q], policy or context(), log)


class PublishedMeiosis(unittest.TestCase):
    def test_reviewed_sibship_counts_only_phased_siblings_and_distinguishes_direct_edge(self):
        from pp1_affected_segregations import ANCHOR

        q = pedigree(1)
        q['identity']['coding'] = 'c.100A>G'
        q['observations'] = [row for row in q['observations'] if row['kind'] != 'proband']
        add(q, 'P', 'conditionalReference', True)
        add(q, 'M', 'genotype', 'NONCARRIER')
        for child in ('P', 'S1', 'S2'):
            for parent in ('F', 'M'):
                add(q, parent, 'parentOf', child)
            add(q, child, 'alleleOriginParent', 'F')
            if child != 'P':
                add(q, child, 'genotype', 'HETEROZYGOUS')
                add(q, child, 'diagnosis', 'TEST disease')
        compiled = context(multi_family_spec())
        compiled['policy'].update(conditioning=ANCHOR, alleleScope={'coding': 'c.100A>G'})
        result = evaluate([q, deepcopy(q)], compiled)
        self.assertEqual(('MET', 'SUPPORTING', 3), (result['status'], result['strength'],
                         result['meiosisAssessment']['informativeMeioses']))
        self.assertEqual({
            ('F', 'S1', 'CONDITIONAL_SIBSHIP', 'P'),
            ('F', 'S2', 'CONDITIONAL_SIBSHIP', 'P'),
            ('P', 'C0', 'DIRECT_PARENT_CHILD', None),
        }, {(event['parentLabel'], event['memberLabel'], event['meiosisBasis'],
             event.get('conditionalReferenceLabel')) for event in result['events']})
        q['observations'] = [row for row in q['observations'] if row['kind'] != 'alleleOriginParent' or row['memberKey'] != 'P']
        rejected = evaluate(q, compiled)
        self.assertEqual(('NOT_EVALUATED', []), (rejected['status'], rejected['events']))
        self.assertIn('PP1_CONDITIONAL_SIBSHIP_PHASE_REQUIRED', [row['reasonCode'] for row in rejected['needs']])

    def test_compiler_reads_rule_and_rejects_added_requirements_or_wrong_scope(self):
        self.assertEqual(METHOD, context()['policy']['method'])
        for change in ('range', 'not_approved', 'clause_condition', 'release_condition', 'other_moi'):
            with self.subTest(change=change):
                spec = meiosis_spec()
                clause = spec['original']['ld']['CriteriaCode'][0]['entContent']
                if change == 'range': clause['strengthDescriptor'][1]['text'] = '4 informative meioses.'
                if change == 'not_approved': clause['strengthDescriptor'][0]['status'] = 'not approved'
                if change == 'clause_condition': clause['additionalComments'] = 'Additional phenotype table required.'
                if change == 'release_condition': spec['original']['entContent']['releaseNotes'] = 'New PP1 conditions apply.'
                if change == 'other_moi': spec['original']['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['preferredModeOfInheritance'] = 'Autosomal recessive inheritance'
                self.assertEqual('METHOD_UNSUPPORTED', context(spec)['status'])

    def test_strength_uses_transmission_thresholds_once_and_pp4_capacity_still_applies(self):
        for n, strength, points in ((1,None,0),(2,'SUPPORTING',1),(3,'MODERATE',2),(4,'MODERATE',2),(5,'STRONG',4),(7,'STRONG',4)):
            with self.subTest(n=n):
                scored = evaluate(pedigree(n))
                self.assertEqual((strength, points), (scored['strength'], scored['awardedPP1Points']))
                self.assertEqual(n, scored['meiosisAssessment']['informativeMeioses'])
                self.assertTrue(all(e['informativeMeioses'] == 1 and 'rawPoints' not in e and e['sourceRefs'] for e in scored['events']))
        limited = evaluate(pedigree(5), pp4=3)
        self.assertEqual(('STRONG','MODERATE',2), (limited['meiosisAssessment']['countStrength'], limited['strength'], limited['awardedPP1Points']))

    def test_carriers_without_origin_cannot_reach_threshold_and_reciprocal_edges_do_not_add(self):
        q = pedigree(2, explicit=False)
        checked = evaluate(q)
        self.assertEqual(('NOT_EVALUATED',0), (checked['status'], checked['awardedPP1Points']))
        self.assertIn('PP1_MEIOSIS_ORIGIN_UNRESOLVED', [n['reasonCode'] for n in checked['needs']])
        add(q, 'OTHER', 'genotype', 'NONCARRIER')
        for child in ('C0','C1'):
            add(q, 'OTHER', 'parentOf', child)
            add(q, child, 'childOf', 'P')
        q['observations'] *= 2
        self.assertEqual(2, evaluate(q)['meiosisAssessment']['informativeMeioses'])
        self.assertEqual(1, evaluate(q)['awardedPP1Points'])

    def test_conflicting_origin_wrong_phenotype_and_disconnected_paths_do_not_award(self):
        q = pedigree(2)
        different = deepcopy(q)
        add(different, 'C0', 'alleleOriginParent', 'OTHER')
        scored = evaluate(different)
        self.assertEqual(('NOT_EVALUATED', 0), (scored['status'],scored['awardedPP1Points']))
        different = deepcopy(q)
        different['observations'] = [r for r in different['observations'] if r['memberKey'] != 'C0' or r['kind'] != 'diagnosis']
        add(different, 'C0', 'clinical', 'AFFECTED')
        self.assertEqual(1, evaluate(different)['meiosisAssessment']['informativeMeioses'])
        other = pedigree(3, family='F2')
        # A family label or another independent-looking source alone is not provenance.
        self.assertEqual(3, evaluate([q, other])['meiosisAssessment']['informativeMeioses'])
        excluded = evaluate(q, exclusive=[next(r['id'] for r in q['observations'] if r['kind']=='alleleOriginParent')])
        self.assertEqual((1,0), (excluded['meiosisAssessment']['informativeMeioses'],excluded['awardedPP1Points']))

    def test_multi_family_rule_uses_exact_thresholds_and_pair_evidence(self):
        compiled = context(multi_family_spec())
        self.assertEqual(2, compiled['policy']['thresholds']['STRONG']['minimumIndependentFamilies'])
        single = evaluate(pedigree(7), compiled)
        self.assertEqual(('NOT_EVALUATED',0), (single['status'], single['awardedPP1Points']))
        self.assertIn('PP1_MEIOSIS_STRENGTH_FAMILY_REQUIREMENT', [n['reasonCode'] for n in single['needs']])
        first, second = pedigree(4), pedigree(3, 'F2')
        duplicate = deepcopy(first)
        self.assertEqual(4, evaluate([first, second, duplicate], compiled)['meiosisAssessment']['informativeMeioses'])
        together = article(first, second, declarations=['Family F1 and family F2 were unrelated.'])
        assessed = evaluate([together, deepcopy(together)], compiled)
        self.assertEqual(('STRONG', 4), (assessed['strength'], assessed['awardedPP1Points']))
        self.assertEqual((7,2), (assessed['meiosisAssessment']['informativeMeioses'], assessed['meiosisAssessment']['qualifiedFamilyCount']))
        self.assertEqual('Family F1 and family F2 were unrelated.', assessed['meiosisAssessment']['familyIndependenceEvidence'][0]['sourceRefs'][0]['quote'])

    def test_two_registered_manual_rosters_require_reviewed_independence(self):
        from pathlib import Path
        from tempfile import TemporaryDirectory
        from unittest.mock import Mock

        from acquire_public_literature import source_bundle
        from bundle_model_contract import cite, load
        from literature_manual_review import VERSION, register as register_review
        from model_literature_contract import canonical, digest
        from paper_library_registration import register
        from pp1_reading_loop import qualify_artifacts
        from prepare_literature import prepare
        from test_literature_acquisition import jats

        pair = 'Family F1 and family F2 were unrelated.'
        raw = jats(False).replace(b'<p>A public source.</p>',
            b'<p>GENE1 NM_000001.7:c.1A&gt;G.</p><p>' + pair.encode() + b'</p>')
        source_sha = digest(raw)
        with TemporaryDirectory() as directory:
            library = Path(directory)
            files, bundle = source_bundle({'pmcid': 'PMC123'}, raw, prepare(raw, 'PMC123', source_sha), None, False)
            pin = register(library, 'PMC123', files, bundle, {'sha256': source_sha, 'acquisitionUrl':
                'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'}, criteria=('PP1',))['bundleSha256']
            source = load(library, 'PMC123', pin, criterion='PP1')
            selection = chosen()
            resolver = Mock()
            resolver.resolve_coding.return_value = {'allele': selection['allele'], 'transcript': 'NM_000001.7'}
            variant_unit = next(unit for unit in source['units'] if 'NM_000001.7' in unit['text'])
            pair_unit = next(unit for unit in source['units'] if pair == unit['text'])
            refs = [cite(source, {'unitId': unit['unitId'], 'quote': unit['text']}) for unit in (variant_unit, pair_unit)]
            scope = {key: selection[key] for key in ('geneSymbol', 'diseaseId', 'inheritance', 'allele')}

            def retain(family, count, reviewed_pair):
                q = pedigree(count, family, explicit=False)
                q['observations'] = [row for row in q['observations'] if row['kind'] != 'candidateMethod']
                q['families'][family]['members'].pop('FAMILY')
                parent = 'P' if family == 'F1' else family + ':P'
                add(q, 'OTHER', 'genotype', 'NONCARRIER', family)
                add(q, 'OTHER', 'clinical', 'UNAFFECTED', family)
                for row in list(q['observations']):
                    if row['kind'] == 'parentOf' and row['memberLabel'] == parent:
                        add(q, 'OTHER', 'parentOf', row['value'], family)
                doc = {'schemaVersion': VERSION, 'criterion': 'PP1',
                       'binding': {'stage': 'SOURCE_REVIEW', 'pmcid': 'PMC123', 'bundleSha256': pin,
                                   'target': {'geneSymbol': 'GENE1', 'variantAliases': ['c.1A>G']}},
                       'review': {'reviewedBy': 'Fixture curator', 'recordSha256': 'c' * 64, 'basis': 'Fixture pedigree'},
                       'material': {'scope': scope, 'families': {family: list(q['families'][family]['members'])},
                                    'observations': [
                                        {'family': family, 'member': row['memberLabel'], 'kind': row['kind'],
                                         'value': row['value'], 'sourceRefs': refs if reviewed_pair else refs[:1]}
                                        for row in q['observations']]}}
                return register_review(library, doc, digest(canonical(doc)))

            first, second, unreviewed = retain('F1', 2, False), retain('F2', 1, True), retain('F2', 1, False)
            compiled = context(multi_family_spec())
            for artifacts, expected in (
                ([first], ('NOT_MET', 2, 1, None, 0)),
                ([second], ('NOT_MET', 1, 1, None, 0)),
                ([first, second], ('MET', 3, 2, 'SUPPORTING', 1)),
                ([first, second, first], ('MET', 3, 2, 'SUPPORTING', 1)),
                ([first, unreviewed], ('NOT_EVALUATED', 2, 1, None, 0)),
            ):
                _, _, qualification = qualify_artifacts(library, artifacts, selection, resolver)
                assessment = evaluate(qualification, compiled)
                counts = assessment['meiosisAssessment']
                self.assertEqual(expected, (assessment['status'], counts['informativeMeioses'],
                    counts['qualifiedFamilyCount'], assessment['strength'], assessment['awardedPP1Points']))
                if expected[0] == 'MET':
                    self.assertEqual(pair, counts['familyIndependenceEvidence'][0]['sourceRefs'][0]['quote'])

    def test_unreviewed_and_conflicting_independence_cannot_raise_strength(self):
        first, second = pedigree(4), pedigree(3, 'F2')
        third = pedigree(3, 'F3')
        compiled = context(multi_family_spec())
        first_pair = 'Family F1 and family F2 were unrelated.'
        for declarations, reviewed in (([first_pair], []),
                                        ([first_pair, 'Families F2 and F1 were related.'], ['0']),
                                        (['If ' + first_pair], ['0']),
                                        (['Families F1 and F2 were unrelated?'], ['0'])):
            with self.subTest(declarations=declarations):
                q = article(first, second, declarations=declarations, reviewed=reviewed)
                self.assertEqual(('SUPPORTING', 4), (evaluate(q, compiled)['strength'], evaluate(q, compiled)['meiosisAssessment']['informativeMeioses']))
        q = article(first, second, third, declarations=[first_pair, 'Families F2 and F3 were unrelated.'])
        self.assertEqual(2, evaluate(q, compiled)['meiosisAssessment']['qualifiedFamilyCount'])
        q = article(first, second, third, declarations=[first_pair, 'Families F1 and F3 were from the same pedigree.',
                                                         'Families F2 and F3 were from the same pedigree.'])
        self.assertEqual(1, evaluate(q, compiled)['meiosisAssessment']['qualifiedFamilyCount'])

    def test_multiple_captures_cannot_mix_different_observations_for_one_pedigree(self):
        q = pedigree(2)
        duplicate = deepcopy(q)
        duplicate['sourceIdentity']['bundleSha256'] = 'b' * 64
        self.assertEqual(2, evaluate([q, duplicate])['meiosisAssessment']['informativeMeioses'])
        duplicate['observations'] = [r for r in duplicate['observations'] if r['kind'] != 'alleleOriginParent']
        add(duplicate, 'C0', 'alleleOriginParent', 'OTHER')
        scored = evaluate([q, duplicate])
        self.assertEqual(('NOT_EVALUATED', 0), (scored['status'], scored['awardedPP1Points']))
        self.assertIn('PP1_MEIOSIS_FAMILY_SNAPSHOTS_DIFFER', [n['reasonCode'] for n in scored['needs']])

    def test_common_ancestry_blocks_summing_named_families_without_conflating_people(self):
        first, second = pedigree(4), pedigree(3, 'F2')
        first['families']['F1']['literalFamilyLabel'] = '1'
        second['families']['F2']['literalFamilyLabel'] = '2'
        independent = 'Family 1 and family 2 were unrelated.'
        common = ('Both haplotype analysis and genealogical examination confirmed the common ancestry of '
                  'families 1–3 sharing the GENE1 c.625 + 1 G > C mutation [9].')
        compiled = context(multi_family_spec())
        self.assertEqual('STRONG', evaluate(article(first, second, declarations=[independent]), compiled)['strength'])
        q = article(first, second, declarations=[independent, common], reviewed=['0'])
        checked = evaluate(q, compiled)
        self.assertEqual(('SUPPORTING', 4, 1), (checked['strength'], checked['meiosisAssessment']['informativeMeioses'],
                                               checked['meiosisAssessment']['qualifiedFamilyCount']))
        self.assertIn(common, [r['quote'] for n in checked['needs'] for r in n['sourceLocators']])

if __name__ == '__main__':
    unittest.main()
