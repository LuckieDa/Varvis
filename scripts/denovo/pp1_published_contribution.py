"""Complete the fresh PP4 occupancy only from a verified whole-rule exclusion."""

REASON = 'PP1_CURRENT_CONTRIBUTION_PUBLISHED_PP4_NOT_APPLICABLE'


def checked_ledger(ledger, context):
    exclusion = context.get('policy', {}).get('pp4Exclusion')
    if (not exclusion or context.get('status') != 'QUALIFIED'
            or context.get('qualificationBasis') != 'ORIGINAL_PUBLISHED_EXPERT_SPECIFICATION'
            or ledger.get('status') != 'UNKNOWN' or ledger.get('pp4Points') is not None
            or ledger.get('reasonCode') != 'PP1_CURRENT_CONTRIBUTION_SOURCE_UNAVAILABLE'
            or 'unmergedCurrentOccupancy' in ledger or ledger.get('eventIds') != []
            or ledger.get('exclusiveFactIds') != [] or ledger.get('scope') != exclusion['scope']):
        return ledger
    return {**ledger, 'status': 'KNOWN_EMPTY', 'reasonCode': REASON, 'pp4Points': 0,
            'publishedPp4Exclusion': exclusion}
