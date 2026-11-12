import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from prepare_expert_inheritance_sources import prepare


class CaptureTest(unittest.TestCase):
    def test_disabled_public_network_never_fetches_or_creates_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); config=root/'config.json'; out=root/'local'/'capture'
            config.write_text(json.dumps({'projectRoot':str(root),'allowPublicNetwork':False}))
            with patch('prepare_expert_inheritance_sources.urlopen') as network:
                with self.assertRaisesRegex(ValueError,'PUBLIC_NETWORK_DISABLED'): prepare(config,out)
            network.assert_not_called(); self.assertFalse(out.exists())

    def test_failed_source_remains_explicit_and_config_is_immutable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); config=root/'config.json'; out=root/'local'/'capture'
            config.write_text(json.dumps({'projectRoot':str(root),'allowPublicNetwork':True}))
            before=config.read_bytes()
            contexts={'contexts':[{'geneSymbol':'GENE1','inheritanceResolution':{'status':'MISSING'}}]}
            with patch('prepare_expert_inheritance_sources.options',return_value=contexts), \
                 patch('prepare_expert_inheritance_sources.urlopen',side_effect=HTTPError('https://example.invalid',404,'missing',{},None)):
                result=prepare(config,out)
            self.assertEqual([],result['sources']);self.assertEqual(404,result['issues'][0]['httpStatus'])
            self.assertEqual(0,result['modelCalls']);self.assertEqual(before,config.read_bytes())
            self.assertTrue((out/'manifest.json').is_file())
            with self.assertRaisesRegex(ValueError,'NEW_LOCAL_OUTPUT_REQUIRED'): prepare(config,out)


if __name__ == '__main__': unittest.main()
