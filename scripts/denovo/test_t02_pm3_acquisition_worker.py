"""Test-only process-boundary transport fixture for the real PM3 worker.

The discovery result and download transport are explicitly injected. The live
LitVar/search discovery function is not exercised. Actual request/config binding,
process_*_discovery, acquisition, immutable state, enrichment and Java gates remain in use.
No source paper is registered and no variant evidence or score is supplied.
"""
from contextlib import nullcontext
from pathlib import Path
import sys
from unittest.mock import patch
from model_literature_contract import parse,canonical
import discover_public_literature
from test_literature_acquisition import FileTest


def main():
    config=parse(Path(sys.argv[sys.argv.index('--config')+1]).read_bytes())
    project=Path(config['projectRoot']);cache=project/config['searchCache'];cache.mkdir(parents=True,exist_ok=True)
    fixture=FileTest();fixture.cache=cache
    candidate=fixture.candidate();transport=fixture.fake(failure='PAPER_DOWNLOAD_TIMEOUT')
    request=parse(Path(sys.argv[sys.argv.index('--request')+1]).read_bytes())
    pp1=request['schemaVersion']=='pp1-public-discovery-task-v1'
    if pp1:
        from run_pp1_paper_task import main as worker_main
    else:
        from run_pm3_paper_task import main as worker_main
    module='process_pp1_discovery' if pp1 else 'process_pm3_discovery'
    def discovery(input_file,*args,**kwargs):
        targets=parse(Path(input_file).read_bytes())['targets']
        from model_literature_contract import digest
        return {'status':'DISCOVERY_ONLY_NOT_QUALIFIED','checkpoint':{'queries':[],
            'searchMode':'LITVAR_ONLY','precisionFirst':True,'deferredCandidates':[],
            'targetSha256':digest(canonical(targets)),
            'implementation':discover_public_literature.implementation_pin()},'networkRequests':0,
            'candidates':[candidate],'resumeAvailable':False,'targets':targets}
    with patch(module+'.discover',side_effect=discovery), patch(
            module+'.DownloadTransport',return_value=nullcontext(transport)):
        worker_main()
    receipt={'sourceClass':'SYNTHETIC_TEST_ONLY_TRANSPORT','actualWorker':('run_pp1_paper_task.main' if pp1 else 'run_pm3_paper_task.main'),
        'actualDiscoveryProcess':module+'.run','actualAcquisition':True,'discoverySearchExecution':'SYNTHETIC_RESULT_NOT_EXECUTED',
        'syntheticDownloadAttempts':transport.requests,'externalNetworkRequests':0,'externalModelCalls':0,
        'registeredPapers':0,'scientificVerdictSupplied':False}
    (project/'local/acquisition-transport-receipt.json').write_bytes(canonical(receipt))


if __name__=='__main__':main()
