import unittest
from model_literature_contract import canonical
from pm3_reading_plan import plan, coverage


def packet(text, location='/article/body/sec/p', context=None):
    units=[{'unitId':location,'text':text}]
    if context: units.append({'unitId':'/context','text':context})
    return {'messages':[{}, {'content':canonical({'units':units,
        'workUnit':{'targetUnitIds':[location],'contextUnitIds':['/context'] if context else []}}).decode()}]}


class ReadingPlan(unittest.TestCase):
    def test_case_gap_promotes_partner_cell_only_in_the_bound_case_row(self):
        from model_literature_contract import parse
        rows=[]
        for i in [9,10]:
            row=packet('p.OtherVariant', f'/article/table[1]/tbody[1]/tr[{i}]/td[3]')
            content=parse(row['messages'][1]['content']);content['units'][0]['kind']='TABLE_CELL'
            row['messages'][1]['content']=canonical(content).decode();rows.append(row)
        p=self.plan(rows,[{'group':'GENOTYPE','reasonCode':'PM3_CASE_PARTNER_OR_HOMOZYGOSITY_UNRESOLVED',
                          'sourceUnitIds':['/article/table[1]/tbody[1]/tr[10]/td[1]']}])
        self.assertEqual([1],p['orderedRelevantChunks'])
        self.assertEqual([0],p['unselectedChunks'])
        self.assertFalse(p['absenceProven'])
        self.assertEqual([1],coverage(p,[])['pendingRelevantChunks'])
    target={'geneSymbol':'EXAMPLE','variantAliases':['NM_000001.1:c.1A>G']}
    def plan(self, packets, needs=None, **kw):
        return plan(packets,self.target,pmcid='PMC123',bundle_pin='a'*64,
            feedback={'pmcid':'PMC123','bundleSha256':'a'*64,'needs':needs or [],**kw})
    def test_missing_phase_changes_order_without_asserting_phase(self):
        p=self.plan([packet('The patient carries c.1A>G.'),packet('Paternal testing was negative.')],
                    [{'group':'PHASE','reasonCode':'PM3_PHASE_REQUIRED'}])
        self.assertEqual([1,0],p['orderedRelevantChunks'])
        self.assertFalse(p['absenceProven'])
        self.assertNotIn('phase',p)
    def test_context_is_not_reviewed_target(self):
        p=self.plan([packet('Administrative material.',context='The father has c.1A>G.')])
        self.assertEqual([],p['orderedRelevantChunks'])
        self.assertEqual([0],coverage(p,[])['pendingAllChunks'])
    def test_conflicting_and_negative_case_text_remains_in_scope(self):
        p=self.plan([packet('The patient was unaffected; the genotype was inconsistent.'),
                     packet('Acknowledgements.', '/article/ack[1]/p[1]')])
        self.assertEqual([0],p['orderedRelevantChunks'])
        self.assertEqual([1],p['unselectedChunks'])
    def test_feedback_cannot_supply_completed_chunks(self):
        p=self.plan([packet('Homozygous patient.')],completedReviewChunks=[0])
        self.assertEqual([0],coverage(p,[])['pendingRelevantChunks'])
        self.assertEqual([],coverage(p,[0])['pendingRelevantChunks'])
    def test_source_mismatch_and_unknown_need_rejected(self):
        with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):
            self.plan([],pmcid='PMC124')
        with self.assertRaisesRegex(ValueError,'NEED_INVALID'):
            self.plan([],[{'group':'MAKE_PATHOGENIC','reasonCode':'PM3_X'}])
    def test_external_gap_does_not_promote_bibliography(self):
        p=self.plan([packet('Pathogenic references.', '/article/ref-list[1]/ref[1]')],
                    [{'group':'EXTERNAL_RESOURCE','reasonCode':'PM3_TARGET_RARITY_NOT_ESTABLISHED'}])
        self.assertEqual([],p['orderedRelevantChunks'])
    def test_complete_selected_scope_does_not_claim_full_paper(self):
        p=coverage(self.plan([packet('Homozygous patient.'),packet('Acknowledgement.')]),[0])
        self.assertEqual([],p['pendingRelevantChunks'])
        self.assertEqual([1],p['pendingAllChunks'])
        self.assertFalse(p['absenceProven'])
    def test_other_variant_table_rows_not_promoted_by_patient_word(self):
        from model_literature_contract import parse
        row=packet('Patient 8, homozygous c.9C>T.')
        content=parse(row['messages'][1]['content']);content['units'][0]['kind']='TABLE_CELL'
        row['messages'][1]['content']=canonical(content).decode()
        p=self.plan([row],[{'group':'CASE','reasonCode':'PM3_CASE_IDENTITY_OR_INDEPENDENCE_UNRESOLVED'}])
        self.assertEqual([],p['orderedRelevantChunks']);self.assertEqual([0],p['unselectedChunks'])


if __name__=='__main__': unittest.main()
