import React from 'react';
import TeachingAcmgEvaluationPanel from './TeachingAcmgEvaluationPanel';
import OrdinaryAcmgEvaluationPanel from './OrdinaryAcmgEvaluationPanel';

export default function TopNAcmgEvaluationPanel({ analysisId = '', candidateCount, onReturnToCandidates }) {
    return analysisId ? (
        <OrdinaryAcmgEvaluationPanel key={analysisId} analysisId={analysisId} candidateCount={candidateCount}
            onReturnToCandidates={onReturnToCandidates} />
    ) : (
        <TeachingAcmgEvaluationPanel />
    );
}
