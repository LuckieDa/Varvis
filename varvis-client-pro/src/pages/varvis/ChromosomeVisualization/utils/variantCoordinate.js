// Display only. Never use this label as an allele identity, query key or HGVS.
export function formatVariantCoordinate(allele, fallback='') {
    const contig=allele?.contig ?? allele?.chromosome;
    const position=allele?.position;
    if (contig==null || !String(contig).trim() || !Number.isSafeInteger(Number(position)) || Number(position)<=0) return fallback;
    let chromosome=String(contig).trim().replace(/^chr/i,'');
    if (!chromosome) return fallback;
    if (/^(x|y|m|mt)$/i.test(chromosome)) chromosome=chromosome.toUpperCase();
    const location=`chr${chromosome}-${Number(position)}`;
    const reference=allele.reference, alternate=allele.alternate;
    return reference!=null && alternate!=null ? `${location} ${reference}>${alternate}` : location;
}
