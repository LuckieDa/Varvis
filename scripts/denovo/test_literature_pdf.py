"""Synthetic PDF bytes: text provenance, grid ambiguity and bounded failures."""
import json
import unittest
from unittest.mock import patch
from pathlib import Path
import subprocess
from literature_attachments import parse_attachment
from literature_pdf import read_pdf


def pdf():
    # Minimal native PDF, two columns and two rows, no test-library dependency.
    stream = (b'0 0 0 RG 20 700 m 220 700 l S 20 680 m 220 680 l S '
              b'20 660 m 220 660 l S 20 660 m 20 700 l S 120 660 m 120 700 l S '
              b'220 660 m 220 700 l S '
              b'BT /F1 10 Tf 25 685 Td (Patient) Tj 100 0 Td (Allele 1) Tj ET '
              b'BT /F1 10 Tf 25 665 Td (Case-1) Tj 100 0 Td (c.123A>T) Tj ET')
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
               b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               b'<< /Length '+str(len(stream)).encode()+b' >>\nstream\n'+stream+b'\nendstream']
    raw=b'%PDF-1.4\n';offsets=[0]
    for i,obj in enumerate(objects,1):
        offsets.append(len(raw));raw+=str(i).encode()+b' 0 obj\n'+obj+b'\nendobj\n'
    start=len(raw);raw+=b'xref\n0 6\n0000000000 65535 f \n'
    raw+=b''.join(f'{x:010} 00000 n \n'.encode() for x in offsets[1:])
    return raw+b'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n'+str(start).encode()+b'\n%%EOF\n'


class Pdf(unittest.TestCase):
    def setUp(self):
        from literature_pdf import _CACHE
        _CACHE.clear()
    def test_legacy_does_not_silently_enable_pdf(self):
        self.assertEqual('PAPER_ATTACHMENT_FORMAT_UNSUPPORTED',parse_attachment('x.pdf',pdf())['reasonCode'])

    def test_real_child_grid_and_original_position(self):
        result=read_pdf(pdf())
        self.assertEqual('CELLS_READ_NOT_SEMANTICALLY_INTERPRETED',result['status'])
        self.assertEqual(['Patient','Allele 1','Case-1','c.123A>T'],[c['text'] for c in result['sheets'][0]['cells']])
        cell=result['sheets'][0]['cells'][-1]
        self.assertEqual(1,cell['page']);self.assertEqual('R2C2',cell['address'])
        self.assertEqual(4,len(cell['bbox']));self.assertEqual('NOT_INFERRED',result['sheets'][0]['headerContinuation'])
        self.assertTrue(all(u['sourcePath'].startswith('/page[1]/line[') for u in result['units']))

    def test_invalid_pdf_and_timeout_fail_explicitly(self):
        r=parse_attachment('x.pdf',b'%PDF-broken',allow_pdf=True)
        self.assertEqual('ARCHIVED_NOT_PARSED',r['status'])
        with patch('literature_pdf.subprocess.run',side_effect=subprocess.TimeoutExpired('worker',30)):
            self.assertEqual('PAPER_ATTACHMENT_PDF_TIMEOUT',parse_attachment('x.pdf',pdf(),allow_pdf=True)['reasonCode'])

    def test_tool_change_and_expansion_budget(self):
        with patch.object(Path,'read_bytes',return_value=b'{}'):
            self.assertEqual('PAPER_ATTACHMENT_PDF_TOOL_UNAVAILABLE_OR_CHANGED',parse_attachment('x.pdf',pdf(),allow_pdf=True)['reasonCode'])
        with self.assertRaisesRegex(ValueError,'EXPANSION_LIMIT'):read_pdf(pdf(),[1])

    def test_memory_cache_is_not_editable_or_free_expansion(self):
        result=read_pdf(pdf());result['sheets'].clear()
        with patch('literature_pdf.subprocess.run',side_effect=AssertionError('duplicate child')):
            self.assertEqual(1,len(read_pdf(pdf())['sheets']))
            with self.assertRaisesRegex(ValueError,'EXPANSION_LIMIT'):read_pdf(pdf(),[1])


if __name__=='__main__':unittest.main()
