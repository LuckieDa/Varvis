import React from 'react';
import { Alert, Link, Typography } from '@mui/material';
import { experimentPrompt, safeSourceUrl } from '../utils/splicingExperimentCheck';

export default function SplicingExperimentPrompt({ candidate, evaluation, snapshot, unavailable }) {
    const prompt = experimentPrompt(candidate, evaluation, snapshot, unavailable);
    if (!prompt) return null;
    return (
        <Alert severity={prompt.severity}>
            <Typography variant="body2" fontWeight={600}>
                实验核验：{prompt.label}
            </Typography>
            <Typography variant="caption" component="div">
                {prompt.reason}
            </Typography>
            {prompt.records.map((record) => (
                <React.Fragment key={record.recordId}>
                    <Link
                        href={safeSourceUrl(record.source?.url)}
                        target="_blank"
                        rel="noopener noreferrer"
                    >
                        {record.geneSymbol} · PMID {record.source?.pmid}
                    </Link>
                    <details>
                        <summary>实验及适用边界</summary>
                        {[
                            record.summary,
                            record.experiment?.system,
                            record.experiment?.limitations,
                            record.diseaseScope,
                        ].map((text, i) => (
                            <Typography variant="caption" component="div" key={i}>
                                {text}
                            </Typography>
                        ))}
                    </details>
                </React.Fragment>
            ))}
        </Alert>
    );
}
