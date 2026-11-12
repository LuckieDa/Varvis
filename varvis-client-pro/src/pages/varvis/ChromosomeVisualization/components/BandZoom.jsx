import React, { useEffect, useMemo, useRef } from 'react';
import Ideogram from 'ideogram';
import { resolveBandRange } from '../utils/bandHelpers';

/** Zoom a single band by passing regions to Ideogram */
const BandZoom = ({
    chromosome,
    band,
    highlightBandName,
    diseaseCount,
    caseVariantCount = 0,
    caseOverlapVariantCount = 0,
    enableDiseaseMarkers = false,
    onBandActivate,
}) => {
    const hostRef = useRef(null);
    const containerId = useMemo(
        () => `cv-band-zoom-${chromosome}-${band?.name || 'band'}`.replace(/[^a-zA-Z0-9-_]/g, '_'),
        [band?.name, chromosome],
    );

    const { start, stop } = useMemo(() => resolveBandRange(band), [band]);

    const [windowStart, windowStop] = useMemo(() => {
        if (!Number.isFinite(start) || !Number.isFinite(stop)) return [null, null];
        const span = Math.max(stop - start, 1e6);
        const padding = Math.max(span * 1.2, 8e6);
        const wStart = Math.max(0, start - padding);
        const wStop = stop + padding;
        return [wStart, wStop];
    }, [start, stop]);

    const chrBands = useMemo(() => {
        if (!band) return {};
        const valid = (band.allBands || [])
            .map((b) => {
                const range = resolveBandRange(b);
                return { ...b, start: range.start, stop: range.stop };
            })
            .filter((b) => Number.isFinite(b.start) && Number.isFinite(b.stop) && b.stop > b.start);
        const filtered =
            Number.isFinite(windowStart) && Number.isFinite(windowStop)
                ? valid.filter((b) => b.stop >= windowStart && b.start <= windowStop)
                : valid;
        const source = filtered.length ? filtered : valid;
        return {
            [chromosome]: source.map((b) => ({
                bp: [b.start, b.stop],
                chr: chromosome,
                name: b.name,
                gieStain: b.gieStain,
            })),
        };
    }, [band, chromosome, windowStart, windowStop]);

    useEffect(() => {
        if (
            !band ||
            !Object.keys(chrBands).length ||
            !Number.isFinite(start) ||
            !Number.isFinite(stop)
        ) {
            return undefined;
        }

        if (hostRef.current) {
            hostRef.current.innerHTML = '';
        }

        const ideo = new Ideogram({
            container: `#${containerId}`,
            organism: 'human',
            chrBands,
            chromosomes: [chromosome],
            orientation: 'horizontal',
            chromosomeHeight: 1500,
            chrWidth: 70,
            rotatable: false,
            showChromosomeLabels: false,
            regions: [{ chr: chromosome, start, stop }],
        });
        return () => ideo?.destroy?.();
    }, [band, chrBands, chromosome, containerId, start, stop]);

    useEffect(() => {
        if (!enableDiseaseMarkers) return undefined;
        if (!band?.name) return undefined;
        const host = hostRef.current;
        if (!host) return undefined;
        const targetName = String(highlightBandName || band.name)
            .trim()
            .toLowerCase();
        let raf = null;
        let observer = null;

        const normalize = (node) => {
            if (!node) return '';
            const dataName = node.getAttribute('data-name');
            if (dataName) return dataName.toLowerCase();
            const id = node.getAttribute('id') || '';
            const marker = '-9606-';
            const idx = id.indexOf(marker);
            if (idx >= 0)
                return id
                    .slice(idx + marker.length)
                    .replace(/-/g, '.')
                    .toLowerCase();
            const token = id.split('-').pop();
            return token ? token.replace(/-/g, '.').toLowerCase() : '';
        };

        const renderMarkers = () => {
            const svg = host.querySelector('svg#_ideogram');
            if (!svg) return false;
            host.style.overflow = 'visible';
            host.style.paddingTop = '28px';
            const wraps = host.querySelectorAll(
                '#_ideogramWrap, #_ideogramOuterWrap, #_ideogramMiddleWrap, #_ideogramInnerWrap, #_ideogram',
            );
            wraps.forEach((node) => {
                node.style.overflow = 'visible';
            });
            svg.setAttribute('overflow', 'visible');
            svg.style.overflow = 'visible';
            const rawViewBox =
                svg.getAttribute('data-cv-original-viewbox') || svg.getAttribute('viewBox');
            if (!svg.getAttribute('data-cv-original-viewbox') && rawViewBox) {
                svg.setAttribute('data-cv-original-viewbox', rawViewBox);
            }
            if (rawViewBox) {
                const parts = rawViewBox
                    .trim()
                    .split(/\s+/)
                    .map((part) => Number(part));
                if (parts.length === 4 && parts.every(Number.isFinite)) {
                    const [vx, vy, vw, vh] = parts;
                    const padTop = 32;
                    svg.setAttribute('viewBox', `${vx} ${vy - padTop} ${vw} ${vh + padTop}`);
                }
            }
            const chromosomeSet =
                svg.querySelector('g.chromosome-set') ||
                svg.querySelector('g[class*="chromosome-set"]');
            if (!chromosomeSet) return false;
            const originalClipPath = chromosomeSet.getAttribute('clip-path');
            if (originalClipPath && !chromosomeSet.getAttribute('data-cv-original-clip-path')) {
                chromosomeSet.setAttribute('data-cv-original-clip-path', originalClipPath);
            }
            chromosomeSet.setAttribute('clip-path', 'none');
            chromosomeSet.style.clipPath = 'none';
            chromosomeSet.style.webkitClipPath = 'none';

            let overlay = chromosomeSet.querySelector('g.cv-disease-overlay');
            if (!overlay) {
                overlay = document.createElementNS('http://www.w3.org/2000/svg', 'g');
                overlay.setAttribute('class', 'cv-disease-overlay');
                overlay.setAttribute('pointer-events', 'none');
                chromosomeSet.appendChild(overlay);
            }
            while (overlay.firstChild) overlay.removeChild(overlay.firstChild);

            const nodes = svg.querySelectorAll('.bands .band');
            const target = Array.from(nodes).find((node) => normalize(node) === targetName);
            if (!target) return false;

            const bbox = target.getBBox();
            const x = bbox.x + bbox.width / 2;
            const yTop = bbox.y;
            const ns = 'http://www.w3.org/2000/svg';
            if (Number.isFinite(caseOverlapVariantCount) && caseOverlapVariantCount > 0) {
                const ringInset = 1.35;
                const ring = document.createElementNS(ns, 'rect');
                const ringWidth = Math.max(2, bbox.width + ringInset * 2);
                const ringHeight = Math.max(2, bbox.height + ringInset * 2);
                ring.setAttribute('x', `${bbox.x - ringInset}`);
                ring.setAttribute('y', `${bbox.y - ringInset}`);
                ring.setAttribute('width', `${ringWidth}`);
                ring.setAttribute('height', `${ringHeight}`);
                ring.setAttribute('rx', `${Math.min(3.2, Math.max(0.8, ringHeight * 0.2))}`);
                ring.setAttribute('ry', `${Math.min(3.2, Math.max(0.8, ringHeight * 0.2))}`);
                ring.setAttribute('class', 'cv-band-case-overlap-ring');
                overlay.appendChild(ring);
            }
            if (Number.isFinite(diseaseCount) && diseaseCount > 0) {
                let stroke = '#059669';
                if (diseaseCount >= 11) stroke = '#ef4444';
                else if (diseaseCount >= 6) stroke = '#f97316';
                const lineLen = 10;
                const y1 = yTop - lineLen;
                const y2 = yTop;
                const lineOutline = document.createElementNS(ns, 'line');
                lineOutline.setAttribute('x1', `${x}`);
                lineOutline.setAttribute('x2', `${x}`);
                lineOutline.setAttribute('y1', `${y1}`);
                lineOutline.setAttribute('y2', `${y2}`);
                lineOutline.setAttribute('stroke', stroke);
                lineOutline.setAttribute('stroke-width', '3');
                lineOutline.setAttribute('stroke-linecap', 'round');
                lineOutline.setAttribute('vector-effect', 'non-scaling-stroke');
                overlay.appendChild(lineOutline);

                const line = document.createElementNS(ns, 'line');
                line.setAttribute('x1', `${x}`);
                line.setAttribute('x2', `${x}`);
                line.setAttribute('y1', `${y1}`);
                line.setAttribute('y2', `${y2}`);
                line.setAttribute('stroke', '#000000');
                line.setAttribute('stroke-width', '1');
                line.setAttribute('stroke-linecap', 'round');
                line.setAttribute('vector-effect', 'non-scaling-stroke');
                overlay.appendChild(line);

                const dot = document.createElementNS(ns, 'circle');
                dot.setAttribute('cx', `${x}`);
                dot.setAttribute('cy', `${y1 + 0.8}`);
                dot.setAttribute('r', '4.25');
                dot.setAttribute('fill', stroke);
                dot.setAttribute('stroke', '#000000');
                dot.setAttribute('stroke-width', '1');
                dot.setAttribute('vector-effect', 'non-scaling-stroke');
                dot.setAttribute('shape-rendering', 'geometricPrecision');
                overlay.appendChild(dot);
            }
            if (Number.isFinite(caseVariantCount) && caseVariantCount > 0) {
                const yBottom = bbox.y + bbox.height;
                const lineLen = 10;
                const y2 = yBottom + lineLen;
                const stroke = '#0d47a1';

                const lineOutline = document.createElementNS(ns, 'line');
                lineOutline.setAttribute('x1', `${x}`);
                lineOutline.setAttribute('x2', `${x}`);
                lineOutline.setAttribute('y1', `${yBottom}`);
                lineOutline.setAttribute('y2', `${y2}`);
                lineOutline.setAttribute('stroke', stroke);
                lineOutline.setAttribute('stroke-width', '3');
                lineOutline.setAttribute('stroke-linecap', 'round');
                lineOutline.setAttribute('vector-effect', 'non-scaling-stroke');
                overlay.appendChild(lineOutline);

                const line = document.createElementNS(ns, 'line');
                line.setAttribute('x1', `${x}`);
                line.setAttribute('x2', `${x}`);
                line.setAttribute('y1', `${yBottom}`);
                line.setAttribute('y2', `${y2}`);
                line.setAttribute('stroke', '#000000');
                line.setAttribute('stroke-width', '1');
                line.setAttribute('stroke-linecap', 'round');
                line.setAttribute('vector-effect', 'non-scaling-stroke');
                overlay.appendChild(line);

                const dot = document.createElementNS(ns, 'circle');
                dot.setAttribute('cx', `${x}`);
                dot.setAttribute('cy', `${y2 - 0.6}`);
                dot.setAttribute('r', '3.9');
                dot.setAttribute('fill', stroke);
                dot.setAttribute('stroke', '#000000');
                dot.setAttribute('stroke-width', '1');
                dot.setAttribute('vector-effect', 'non-scaling-stroke');
                dot.setAttribute('shape-rendering', 'geometricPrecision');
                overlay.appendChild(dot);
            }
            chromosomeSet.appendChild(overlay);

            // Debug marker removed (alignment confirmed)

            if (!observer) {
                observer = new MutationObserver(() => {
                    renderMarkers();
                });
                observer.observe(host, { childList: true, subtree: true });
            }
            return true;
        };

        const loop = () => {
            if (renderMarkers()) return;
            raf = requestAnimationFrame(loop);
        };
        loop();

        return () => {
            if (raf) cancelAnimationFrame(raf);
            observer?.disconnect();
            const svg = host.querySelector('svg#_ideogram');
            const overlay = svg?.querySelector('g.cv-disease-overlay');
            if (overlay) overlay.remove();
            const chromosomeSet =
                svg?.querySelector('g.chromosome-set') ||
                svg?.querySelector('g[class*="chromosome-set"]');
            if (chromosomeSet) {
                const storedClipPath = chromosomeSet.getAttribute('data-cv-original-clip-path');
                if (storedClipPath) {
                    chromosomeSet.setAttribute('clip-path', storedClipPath);
                    chromosomeSet.removeAttribute('data-cv-original-clip-path');
                } else {
                    chromosomeSet.removeAttribute('clip-path');
                }
                chromosomeSet.style.clipPath = '';
                chromosomeSet.style.webkitClipPath = '';
            }
        };
    }, [
        band?.name,
        caseVariantCount,
        caseOverlapVariantCount,
        diseaseCount,
        highlightBandName,
        enableDiseaseMarkers,
    ]);

    useEffect(() => {
        if (!band?.name) return undefined;
        const host = hostRef.current;
        if (!host) return undefined;
        let raf = null;

        const centerSelectedBand = () => {
            const svg = host.querySelector('svg#_ideogram');
            if (!svg) {
                raf = requestAnimationFrame(centerSelectedBand);
                return;
            }
            const target = Array.from(svg.querySelectorAll('.bands .band')).find((node) => {
                const dataName = node.getAttribute('data-name');
                if (dataName) return dataName === band.name;
                const id = node.getAttribute('id') || '';
                const marker = '-9606-';
                const idx = id.indexOf(marker);
                if (idx >= 0) {
                    return id.slice(idx + marker.length).replace(/-/g, '.') === band.name;
                }
                const token = id.split('-').pop();
                return token ? token.replace(/-/g, '.') === band.name : false;
            });
            if (!target) {
                raf = requestAnimationFrame(centerSelectedBand);
                return;
            }
            const hostRect = host.getBoundingClientRect();
            const bandRect = target.getBoundingClientRect();
            const hostCenter = hostRect.left + hostRect.width / 2;
            const bandCenter = bandRect.left + bandRect.width / 2;
            const offset = hostCenter - bandCenter;
            svg.style.setProperty('--cv-zoom-shift', `${offset}px`);
        };

        centerSelectedBand();
        return () => {
            if (raf) cancelAnimationFrame(raf);
            const svg = host.querySelector('svg#_ideogram');
            if (svg) {
                svg.style.removeProperty('--cv-zoom-shift');
            }
        };
    }, [band?.name]);

    useEffect(() => {
        if (!band?.name) return undefined;
        const host = hostRef.current;
        if (!host) return undefined;
        const targetName = String(band.name).trim();
        let raf = null;
        let observer = null;

        const normalize = (node) => {
            if (!node) return '';
            const dataName = node.getAttribute('data-name');
            if (dataName) return dataName;
            const id = node.getAttribute('id') || '';
            const marker = '-9606-';
            const idx = id.indexOf(marker);
            if (idx >= 0) return id.slice(idx + marker.length).replace(/-/g, '.');
            const token = id.split('-').pop();
            return token ? token.replace(/-/g, '.') : '';
        };

        const highlight = () => {
            const svg = host.querySelector('svg');
            if (!svg) {
                raf = requestAnimationFrame(highlight);
                return;
            }
            const nodes = svg.querySelectorAll('.bands .band');
            if (!nodes.length) {
                raf = requestAnimationFrame(highlight);
                return;
            }
            const target = Array.from(nodes).find((node) => normalize(node) === targetName);
            if (!target) {
                raf = requestAnimationFrame(highlight);
                return;
            }
            svg.querySelectorAll('.cv-band-highlight').forEach((el) =>
                el.classList.remove('cv-band-highlight'),
            );
            target.classList.add('cv-band-highlight');
        };

        highlight();
        observer = new MutationObserver(() => highlight());
        observer.observe(host, { childList: true, subtree: true });

        return () => {
            if (raf) cancelAnimationFrame(raf);
            observer?.disconnect();
            const svg = host.querySelector('svg');
            svg?.querySelectorAll('.cv-band-highlight').forEach((el) =>
                el.classList.remove('cv-band-highlight'),
            );
        };
    }, [band?.name]);

    useEffect(() => {
        if (!band?.name || typeof onBandActivate !== 'function') return undefined;
        const host = hostRef.current;
        if (!host) return undefined;
        const targetName = String(highlightBandName || band.name).trim();
        let raf = null;
        let detach = null;

        const normalize = (node) => {
            if (!node) return '';
            const dataName = node.getAttribute('data-name');
            if (dataName) return dataName;
            const id = node.getAttribute('id') || '';
            const marker = '-9606-';
            const idx = id.indexOf(marker);
            if (idx >= 0) return id.slice(idx + marker.length).replace(/-/g, '.');
            const token = id.split('-').pop();
            return token ? token.replace(/-/g, '.') : '';
        };

        const bind = () => {
            const svg = host.querySelector('svg#_ideogram');
            if (!svg) return false;
            const nodes = Array.from(svg.querySelectorAll('.bands .band'));
            if (!nodes.length) return false;

            nodes.forEach((node) => {
                node.style.cursor = normalize(node) === targetName ? 'pointer' : '';
            });

            const findBandByPoint = (pageX, pageY) =>
                nodes.find((node) => {
                    const rect = node.getBoundingClientRect();
                    return (
                        pageX >= rect.left &&
                        pageX <= rect.right &&
                        pageY >= rect.top &&
                        pageY <= rect.bottom
                    );
                });

            const handleSvgClick = (event) => {
                const hit =
                    event.target?.closest?.('.bands .band') ||
                    findBandByPoint(event.clientX, event.clientY);
                if (!hit) return;
                if (normalize(hit) !== targetName) return;
                event.preventDefault();
                event.stopPropagation();
                onBandActivate();
            };

            svg.addEventListener('click', handleSvgClick);
            detach = () => {
                svg.removeEventListener('click', handleSvgClick);
            };
            return true;
        };

        const loop = () => {
            if (bind()) return;
            raf = requestAnimationFrame(loop);
        };
        loop();

        return () => {
            if (raf) cancelAnimationFrame(raf);
            detach?.();
            const svg = host.querySelector('svg#_ideogram');
            svg?.querySelectorAll('.bands .band').forEach((node) => {
                node.style.cursor = '';
            });
        };
    }, [band?.name, highlightBandName, onBandActivate]);

    if (!band) return null;
    return <div id={containerId} className="cv-band-zoom" ref={hostRef} />;
};

export default BandZoom;
