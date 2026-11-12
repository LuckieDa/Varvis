"""After-claim public source reading and bounded observation review. No disease association is granted."""
import argparse
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'denovo'))
from model_literature_contract import canonical, digest, parse
from pp2_relation_reading import read_scope
from pp2_bounded_literature_qualification import qualify_read_scope, VERSION as QUALIFICATION_VERSION


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--input', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--work', required=True)
    args = parser.parse_args()
    raw = Path(args.input).read_bytes()
    if len(raw) > 4 * 1024 * 1024:
        raise ValueError('PP2_READING_INPUT_TECHNICAL_LIMIT')
    request = parse(raw)
    if request.get('schemaVersion') != 'pp2-after-claim-reading-input-v1':
        raise ValueError('PP2_READING_INPUT_SCHEMA_INVALID')
    ledger = read_scope(request['workflowConfigurationPath'], request['workflowConfigurationRawSha256'],
        request['candidates'], request['knownCitationReferences'], args.work)
    # This receipt concerns actual transport input, not a new source qualification.
    output = dict(schemaVersion='pp2-after-claim-reading-result-v1', inputRawSha256=digest(raw),
                  scientificAdoption=False, readingLedger=ledger)
    target=request.get('technicalTarget')
    if isinstance(target,dict):
        # Local-only qualification input; never sent to the model or retrieval transport.
        try:
            output['boundedLiteratureReview']=qualify_read_scope(request['workflowConfigurationPath'],
                request['workflowConfigurationRawSha256'], ledger, args.work, target, request['knownCitationReferences'], request['candidates'])
        except (ValueError,KeyError,TypeError,OSError) as error:
            reason=str(error) if str(error).startswith('PP2_QUALIFICATION_') and str(error).replace('_','').isalnum() else 'PP2_QUALIFICATION_LOCAL_REPLAY_FAILED'
            output['boundedLiteratureReview']=dict(schemaVersion='pp2-bounded-literature-review-v1',qualificationVersion=QUALIFICATION_VERSION,
                materialIdentity={k:target.get(k) for k in ('nativeModelSha256','alleleAssociationSha256','contextSha256','hcmPolicyBindingSha256')},
                targetAllele=target.get('allele'),scopeMeaning='DECLARED_AVAILABLE_MAIN_AND_PARSED_ATTACHMENT_TEXT_UNITS_ONLY',
                wholePublicationRead=False,scopeStatus='INCOMPLETE',
                papers=[dict(**{k:p[k] for k in ('pmid','pmcid','sourceRawSha256','bundleSha256') if k in p},
                    scopeStatus='INCOMPLETE',observations=[],unresolvedRecords=0,unresolvedUnitIds=[],gaps=[dict(reasonCode=reason)]) for p in ledger['papers']],
                declaredCandidateCount=len(request['candidates']),declaredKnownCitationCount=len(request['knownCitationReferences']),
                unresolvedRecords=0,unresolvedUnits=0,
                unresolvedCitationRefs=[],gaps=[dict(reasonCode=reason)],positiveOtherPhenotypeGranted=False,scientificClassificationCreated=False)
    Path(args.output).write_bytes(canonical(output))


if __name__ == '__main__':
    main()
