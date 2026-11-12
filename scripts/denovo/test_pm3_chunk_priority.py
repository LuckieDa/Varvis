import unittest
from model_literature_contract import canonical
from pm3_chunk_priority import order

class Priority(unittest.TestCase):
    def packet(self,text,context=''):
        return {'messages':[{}, {'content':canonical({'workUnit':{'targetUnitIds':['target']},
                'units':[{'unitId':'target','text':text},{'unitId':'context','text':context}]}).decode()}]}
    def test_target_passage_first_without_discarding_other_sections(self):
        chunks=[self.packet('Authors contributed'),self.packet('c.1208C>T in case 1'),
                self.packet('Additional family findings','A403V'),self.packet('A403Val is a different token')]
        self.assertEqual([1,2,0,3],order(chunks,{'variantAliases':['NM_000277.3:c.1208C>T','A403V']}))
    def test_no_match_is_not_absence_and_order_is_unchanged(self):
        self.assertEqual([0,1],order([self.packet('methods'),self.packet('limitations')],{'variantAliases':['c.2C>G']}))

    def test_vep_three_letter_protein_reads_one_letter_case_before_background(self):
        packets=[self.packet('background'),self.packet('Patient 148 p.A403V'),self.packet('A403Val is different')]
        self.assertEqual([1,0,2],order(packets,{'variantAliases':['p.Ala403Val']}))

if __name__=='__main__':unittest.main()
