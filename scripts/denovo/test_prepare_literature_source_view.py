"""Workbench crop requests use the local artifact renderer, without retrieval or evaluation."""
import unittest
from pathlib import Path
from unittest.mock import patch
import prepare_literature_source_view as view


class WorkbenchSourceRequestTest(unittest.TestCase):
    def test_only_requested_paper_regions_are_passed_to_existing_renderer(self):
        refs = [{'pmcid': 'PMC123', 'locator': '/article[1]/body[1]/p[1]',
                 'quote': 'A precise original quotation.'}]
        with patch.object(view, 'paths', return_value=(Path('library'), Path('node'), Path('browser'))), \
                patch.object(view, 'build', return_value={'generated': 1}) as build:
            self.assertEqual({'generated': 1}, view.prepare({}, {'references': refs}))
        document = build.call_args.args[0]['documents'][0]
        self.assertFalse(document['includeGallery'])
        self.assertEqual('PMC123', document['pmcid'])
        self.assertEqual(refs[0]['quote'], document['cases'][0]['rows'][0]['sourceRefs'][0]['quote'])

    def test_invalid_or_unbounded_references_never_reach_renderer(self):
        for refs in ([], [{}], [{'pmcid': '../private'}], [{}] * 33,
                     [{'pmcid': 'PMC123', 'locator': '/private/file', 'quote': 'text'}]):
            with self.subTest(refs=refs), patch.object(view, 'build') as build:
                with self.assertRaises(ValueError):
                    view.prepare({}, {'references': refs})
                build.assert_not_called()


if __name__ == '__main__':
    unittest.main()
