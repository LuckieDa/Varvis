import copy
import json
import tempfile
import unittest
from pathlib import Path
from combined_protein_region_facts import combine, query, interpro_payload, pinned_json, load
from decipher_repeat_method import parse_output
from protein_repeat_method import sha


class CombinedFactsTest(unittest.TestCase):
    def test_operational_completeness_is_explicit_and_missing_is_not_empty(self):
        f = self.combine()
        self.assertEqual(f['adapterVersion'], '1.1.0')
        self.assertTrue(f['sourceCompleteness']['exactFullSequenceMatch'])
        self.assertEqual(f['sourceCompleteness']['interproEntryCount'], self.entries['count'])
        del self.uni['features']
        with self.assertRaises(ValueError): self.combine()

    def test_missing_pagination_field_cannot_assert_complete(self):
        for key in ('next', 'previous'):
            original = self.entries.pop(key)
            with self.assertRaises(ValueError): self.combine()
            self.entries[key] = original

    def test_compositional_bias_is_preserved_with_exact_or_unplaced_coordinates(self):
        f = dict(type='Compositional bias', location=dict(start=dict(value=2, modifier='EXACT'), end=dict(value=6, modifier='EXACT')))
        self.uni['features'] = [f]
        self.assertEqual(self.combine()['regions'][0]['kind'], 'Compositional bias')
        f['location']['start']['modifier'] = 'UNKNOWN'
        self.assertEqual(len(self.combine()['unplaced']), 1)

    def setUp(self):
        self.seq = 'ACDEACDEACDE'
        self.pin = sha(self.seq.encode())
        self.uni = dict(primaryAccession='P12345', organism=dict(taxonId=9606),
                        sequence=dict(value=self.seq, length=12), features=[])
        self.protein = dict(metadata=dict(accession='P12345', source_organism=dict(taxId='9606'),
                            length=12, sequence=self.seq, is_fragment=False, source_database='reviewed'))
        self.entry = dict(metadata=dict(accession='IPR000001', type='repeat', source_database='interpro',
                            go_terms=[dict(identifier='GO:0005515')]),
                          proteins=[dict(accession='p12345', protein_length=12, organism='9606',
                            entry_protein_locations=[dict(fragments=[dict(start=2,end=4,**{'dc-status':'CONTINUOUS'})])])])
        self.entries = dict(count=1,next=None,previous=None,results=[self.entry])

    def combine(self): return combine('P12345',self.seq,self.uni,self.protein,self.entries,{})

    def test_interpro_positive_and_no_negative_from_empty_region(self):
        facts=self.combine()
        self.assertEqual(query(facts,self.pin,3,3)['repeatState'],'SOURCE_REPEAT_OVERLAP')
        result=query(facts,self.pin,8,9)
        self.assertEqual(result['repeatState'],'UNKNOWN')
        self.assertFalse(result['nonRepeatQualified'])
        self.assertFalse(result['noKnownFunctionQualified'])

    def test_full_sequence_not_length_match(self):
        self.protein['metadata']['sequence']='D'+self.seq[1:]
        with self.assertRaisesRegex(ValueError,'FULL_SEQUENCE'): self.combine()

    def test_fragment_taxon_accession_and_length_reject(self):
        for key,value in [('is_fragment',True),('accession','Q12345'),('source_database','unreviewed'),('length',13)]:
            old=self.protein['metadata'][key];self.protein['metadata'][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):self.combine()
            self.protein['metadata'][key]=old
        self.protein['metadata']['source_organism']['taxId']='10090'
        with self.assertRaises(ValueError):self.combine()

    def test_paging_count_and_duplicate_entry_reject(self):
        for key,value in [('next','page2'),('previous','page0'),('count',2)]:
            old=self.entries[key];self.entries[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):self.combine()
            self.entries[key]=old
        self.entries['results'].append(copy.deepcopy(self.entry));self.entries['count']=2
        with self.assertRaises(ValueError):self.combine()

    def test_discontinuous_gap_is_not_hull_hit(self):
        loc=self.entry['proteins'][0]['entry_protein_locations'][0]
        loc['fragments']=[dict(start=2,end=3,**{'dc-status':'C_TERMINAL_DISC'}),
                          dict(start=8,end=9,**{'dc-status':'N_TERMINAL_DISC'})]
        facts=self.combine()
        self.assertEqual(query(facts,self.pin,5,6)['repeatState'],'UNKNOWN')
        self.assertEqual(query(facts,self.pin,8,8)['repeatState'],'SOURCE_REPEAT_OVERLAP')

    def test_domain_name_or_go_does_not_grant_repeat_or_function(self):
        for kind in ['domain','family','homologous_superfamily']:
            self.entry['metadata']['type']=kind;self.entry['metadata']['name']='Repeat binding'
            result=query(self.combine(),self.pin,2,4)
            self.assertEqual(result['repeatState'],'UNKNOWN')
            self.assertFalse(result['noKnownFunctionQualified'])
            expected='DOMAIN_LEAD' if kind=='domain' else 'PROTEIN_CONTEXT'
            self.assertEqual(result['sourceOtherHits'][0]['category'],expected)

    def test_uniprot_native_and_isoform_locations(self):
        feature=dict(type='Repeat',location=dict(start=dict(value=6,modifier='EXACT'),end=dict(value=7,modifier='EXACT')))
        self.uni['features']=[feature]
        self.assertEqual(query(self.combine(),self.pin,6,6)['repeatState'],'SOURCE_REPEAT_OVERLAP')
        feature['location']['sequence']='P12345-2'
        result=query(self.combine(),self.pin,6,6)
        self.assertEqual(result['repeatState'],'UNKNOWN')
        self.assertIn('UNMAPPED_SOURCE_FEATURES',result['reasons'])

    def test_imprecise_uniprot_coordinates_do_not_become_exact(self):
        self.uni['features']=[dict(type='Repeat',location=dict(start=dict(value=6,modifier='OUTSIDE'),
                                                              end=dict(value=7,modifier='EXACT')))]
        self.assertEqual(len(self.combine()['unplaced']),1)

    def test_invalid_source_and_query_coordinates(self):
        facts=self.combine()
        for a,b in [(0,3),(2,13),(8,7),(True,1)]:
            with self.assertRaises(ValueError):query(facts,self.pin,a,b)
        self.entry['proteins'][0]['entry_protein_locations'][0]['fragments'][0]['end']=13
        with self.assertRaises(ValueError):self.combine()

    def test_source_positive_survives_method_no_hit_or_failure(self):
        facts=self.combine()
        nohit=parse_output(b'BEGIN\tP12345\t3.8.1\tFALSE\nEND\tP12345\t0\n','P12345',self.seq,False)
        for obs in [dict(status='COMPLETED',sequenceSha256=self.pin,facts=nohit),
                    dict(status='TIMEOUT',sequenceSha256=self.pin)]:
            result=query(facts,self.pin,2,4,[obs])
            self.assertEqual(result['repeatState'],'SOURCE_REPEAT_OVERLAP')
            self.assertFalse(result['clinicalAdoption'])

    def test_method_and_query_sequence_identity(self):
        facts=self.combine()
        with self.assertRaises(ValueError):query(facts,'0'*64,2,4)
        with self.assertRaises(ValueError):query(facts,self.pin,2,4,[dict(sequenceSha256='0'*64,status='TIMEOUT')])
        fake=dict(sequenceSha256=self.pin,status='COMPLETED',facts=dict(sequenceSha256='0'*64))
        with self.assertRaises(ValueError):query(facts,self.pin,2,4,[fake])

    def test_release_and_endpoint_binding(self):
        receipt=dict(success=True,status=200,url='expected',resolvedUrl='expected',
                     headers={'InterPro-Version':'110.0','InterPro-Version-Minor':'0'})
        interpro_payload({},receipt,'expected','110.0','0')
        for key,value in [('url','other'),('resolvedUrl','other'),('success',False),('status',206)]:
            bad=copy.deepcopy(receipt);bad[key]=value
            with self.assertRaises(ValueError):interpro_payload({},bad,'expected','110.0','0')
        with self.assertRaises(ValueError):interpro_payload({},receipt,'expected','109.0','0')
        with self.assertRaises(ValueError):interpro_payload({},receipt,'expected','110.0','1')

    def test_bytes_pin_bound_and_path(self):
        with tempfile.TemporaryDirectory() as temp:
            base=Path(temp);(base/'a.json').write_bytes(b'{}');spec=dict(path='a.json',sha256=sha(b'{}'))
            self.assertEqual(pinned_json(base,spec),{})
            with self.assertRaises(ValueError):pinned_json(base,spec,1)
            with self.assertRaises(ValueError):pinned_json(base,dict(path='../escape',sha256=spec['sha256']))
            (base/'a.json').write_bytes(b'[]')
            with self.assertRaises(ValueError):pinned_json(base,spec)

    def acquired_bundle(self, base):
        bodies=dict(uniprot=self.uni,protein=self.protein,entries=self.entries,
                    root=dict(databases=dict(uniprot=dict(version='2026_03'),interpro=dict(version='110.0'))))
        urls=dict(root='https://www.ebi.ac.uk/interpro/api/',
                  protein='https://www.ebi.ac.uk/interpro/api/protein/uniprot/P12345/',
                  entries='https://www.ebi.ac.uk/interpro/api/entry/interpro/protein/uniprot/P12345/?page_size=200')
        specs={}
        def put(key,body):
            raw=json.dumps(body).encode();(base/(key+'.json')).write_bytes(raw)
            specs[key]=dict(path=key+'.json',sha256=sha(raw))
        for key,body in bodies.items():put(key,body)
        for key,url in urls.items():
            put(key+'Receipt',dict(success=True,status=200,url=url,resolvedUrl=url,sha256=specs[key]['sha256'],
                 headers={'InterPro-Version':'110.0','InterPro-Version-Minor':'0'}))
        put('uniprotAcquisition',[dict(accession='P12345',success=True,status=200,
            url='https://rest.uniprot.org/uniprotkb/P12345.json',sha256=specs['uniprot']['sha256'],
            headers='X-UniProt-Release: 2026_03\n')])
        return dict(accession='P12345',sequence=self.seq,sources=specs,
                    interproRelease='110.0',interproMinor='0',uniprotRelease='2026_03')

    def test_complete_pinned_source_chain(self):
        with tempfile.TemporaryDirectory() as temp:
            base=Path(temp);spec=self.acquired_bundle(base)
            self.assertEqual(load(base,spec)['sequenceSha256'],self.pin)
            # Updating a manifest hash does not excuse incompatible release or receipt binding.
            for key,edit in [('proteinReceipt',lambda b:b.update(sha256='0'*64)),
                             ('entriesReceipt',lambda b:b['headers'].update({'InterPro-Version':'109.0'})),
                             ('root',lambda b:b['databases']['uniprot'].update(version='2025_01')),
                             ('uniprotAcquisition',lambda b:b[0].update(headers='X-UniProt-Release: other'))]:
                spec=self.acquired_bundle(base);path=base/spec['sources'][key]['path']
                body=json.loads(path.read_bytes());edit(body);raw=json.dumps(body).encode();path.write_bytes(raw)
                spec['sources'][key]['sha256']=sha(raw)
                with self.subTest(key=key),self.assertRaises(ValueError):load(base,spec)


if __name__ == '__main__':unittest.main()
