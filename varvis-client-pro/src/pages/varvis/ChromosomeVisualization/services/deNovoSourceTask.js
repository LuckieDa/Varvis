import axios from 'axios';

export const fetchDeNovoSourceTask = async (analysisId, runId, workflow = 'CURRENT', signal) => {
    const response = await axios.get(
        `/variant-analyses/${encodeURIComponent(analysisId)}/acmg/runs/${encodeURIComponent(runId)}/denovo-source`,
        { params: { workflow }, timeout: 10000, signal },
    );
    return response.data;
};
