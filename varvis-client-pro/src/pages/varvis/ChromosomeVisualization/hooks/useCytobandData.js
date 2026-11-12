import { useCallback, useEffect, useState } from 'react';
import { fetchCytobands, fetchChromosomeOverview, getCachedCytobands } from '../services/cytobands';

export function useCytobandData() {
    const [initialCytobands] = useState(() => getCachedCytobands());
    const [loading, setLoading] = useState(!initialCytobands);
    const [error, setError] = useState(null);
    const [cytobands, setCytobands] = useState(initialCytobands || {});
    const [chromosomeSummaries, setChromosomeSummaries] = useState({});
    const [loadVersion, setLoadVersion] = useState(0);
    const retry = useCallback(() => setLoadVersion((version) => version + 1), []);

    useEffect(() => {
        let alive = true;
        async function load() {
            if (!initialCytobands) setLoading(true);
            setError(null);
            try {
                const bands = await fetchCytobands();
                if (!alive) return;
                setCytobands(bands);
                // Chromosome geometry is ready; optional statistics must not gate its display.
                setLoading(false);
                const chrList = Object.keys(bands || {});
                const overviewResponses = await Promise.allSettled(
                    chrList.map((chr) => fetchChromosomeOverview(chr)),
                );
                if (!alive) return;
                const summaryMap = {};
                overviewResponses.forEach((result, idx) => {
                    if (result.status !== 'fulfilled') return;
                    const summary = result.value;
                    const chr = chrList[idx];
                    if (summary) summaryMap[chr] = summary;
                });
                setChromosomeSummaries(summaryMap);
                try {
                    window.cytobands = bands;
                    window.chromosomeSummaries = summaryMap;
                } catch (err) {
                    // ignore if window is unavailable
                }
                setLoading(false);
            } catch (e) {
                if (!alive) return;
                setError(e);
                setLoading(false);
            }
        }
        load();
        return () => {
            alive = false;
        };
    }, [initialCytobands, loadVersion]);

    return { loading, error, cytobands, chromosomeSummaries, retry };
}
