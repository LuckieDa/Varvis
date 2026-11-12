package org.nbscn.varvis.service.variant.impl;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.nbscn.varvis.dto.variant.response.CaseBandOverlapDTO;
import org.nbscn.varvis.dto.variant.response.CaseMatchedVariantDTO;
import org.nbscn.varvis.dto.variant.response.CaseVcfUploadResponseDTO;
import org.nbscn.varvis.dto.variant.response.CytobandDTO;
import org.nbscn.varvis.dto.variant.response.SmallVariantDTO;
import org.nbscn.varvis.dto.variant.response.StructuralVariantDTO;
import org.nbscn.varvis.exception.InvalidVcfUploadException;
import org.nbscn.varvis.mapper.varvis.VariantOverviewMapper;
import org.nbscn.varvis.service.variant.VariantCaseService;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.nbscn.varvis.utils.VcfUploadUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "varvis.datasource", name = "enabled", havingValue = "true")
public class VariantCaseServiceImpl implements VariantCaseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantCaseServiceImpl.class);

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_PAGE_SIZE = 200;
    private static final int MAX_PAGE_SIZE = 50000;
    private static final long STRUCTURAL_SIZE_THRESHOLD = 50L;
    private static final int KNOWLEDGE_MATCH_BATCH_SIZE = 500;
    private static final String DEFAULT_CLINICAL = "Not classified";
    private static final String UNKNOWN_CLINICAL = "unknown";

    private final Map<String, CaseDataset> caseStore = new ConcurrentHashMap<String, CaseDataset>();
    private final NamedParameterJdbcTemplate varvisNamedJdbcTemplate;
    private final VariantOverviewMapper variantOverviewMapper;

    public VariantCaseServiceImpl(@Qualifier("varvisNamedParameterJdbcTemplate")
                                  NamedParameterJdbcTemplate varvisNamedJdbcTemplate,
                                  VariantOverviewMapper variantOverviewMapper) {
        this.varvisNamedJdbcTemplate = varvisNamedJdbcTemplate;
        this.variantOverviewMapper = variantOverviewMapper;
    }

    @Override
    public CaseVcfUploadResponseDTO uploadCaseVcf(MultipartFile vcfFile, String genomeAssembly) throws IOException {
        VcfUploadUtils.UploadMetadata metadata = VcfUploadUtils.validateUpload(vcfFile);
        String normalizedGenomeAssembly = requireGenomeAssembly(genomeAssembly);

        String caseId = UUID.randomUUID().toString().replace("-", "");
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "varvis-case-vcf");
        Path tempFile = VcfUploadUtils.createStoredFile(tempDir, metadata.getExtension());
        try (InputStream inputStream = vcfFile.getInputStream()) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        CaseDataset dataset;
        try {
            dataset = parseVcf(caseId, metadata.getOriginalFileName(), normalizedGenomeAssembly, tempFile);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // ignore cleanup failures
            }
        }
        caseStore.put(caseId, dataset);
        return toUploadResponse(dataset);
    }

    @Override
    public boolean removeCase(String caseId) {
        String key = normalizeCaseId(caseId);
        if (!StringUtils.hasText(key)) {
            return false;
        }
        return caseStore.remove(key) != null;
    }

    @Override
    public List<SmallVariantDTO> listCaseSmallVariants(String caseId,
                                                       String chromosome,
                                                       Long startBp,
                                                       Long endBp,
                                                       Integer page,
                                                       Integer pageSize) {
        CaseDataset dataset = resolveCase(caseId);
        if (dataset == null) {
            return Collections.emptyList();
        }
        Filter filter = buildFilter(chromosome, startBp, endBp);
        List<CaseVariantRecord> scoped = filterCaseVariants(dataset.smallByChromosome, filter);
        List<CaseVariantRecord> paged = paginate(scoped, page, pageSize);
        if (paged.isEmpty()) {
            return Collections.emptyList();
        }
        return paged.stream()
                .map(row -> toSmallVariant(row, null, null, null))
                .collect(Collectors.toList());
    }

    @Override
    public List<StructuralVariantDTO> listCaseStructuralVariants(String caseId,
                                                                 String chromosome,
                                                                 Long startBp,
                                                                 Long endBp,
                                                                 Integer page,
                                                                 Integer pageSize) {
        CaseDataset dataset = resolveCase(caseId);
        if (dataset == null) {
            return Collections.emptyList();
        }
        Filter filter = buildFilter(chromosome, startBp, endBp);
        List<CaseVariantRecord> scoped = filterCaseVariants(dataset.structuralByChromosome, filter);
        List<CaseVariantRecord> paged = paginate(scoped, page, pageSize);
        if (paged.isEmpty()) {
            return Collections.emptyList();
        }
        return paged.stream()
                .map(row -> toStructuralVariant(row, null, null, null))
                .collect(Collectors.toList());
    }

    @Override
    public List<SmallVariantDTO> listCaseSmallVariantsByGene(String caseId,
                                                             String geneSymbol,
                                                             String chromosome,
                                                             Long startBp,
                                                             Long endBp,
                                                             Integer page,
                                                             Integer pageSize) {
        CaseDataset dataset = resolveCase(caseId);
        if (dataset == null) {
            return Collections.emptyList();
        }
        String normalizedGene = normalizeGeneSymbol(geneSymbol);
        if (!StringUtils.hasText(normalizedGene)) {
            return Collections.emptyList();
        }
        Filter filter = buildFilter(chromosome, startBp, endBp);
        List<CaseVariantRecord> scoped = filterCaseVariants(dataset.smallByChromosome, filter);
        List<CaseVariantRecord> geneScoped = filterByGeneSymbol(scoped, normalizedGene);
        List<CaseVariantRecord> paged = paginate(geneScoped, page, pageSize);
        if (paged.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, String> knowledgeClinicalByKey = queryKnowledgeSmallClinical(paged);
        return paged.stream()
                .map(row -> {
                    String key = buildSmallMatchKey(row);
                    boolean knowledgeMatched = StringUtils.hasText(key) && knowledgeClinicalByKey.containsKey(key);
                    String knowledgeClinical = knowledgeClinicalByKey.get(key);
                    return toSmallVariant(row, normalizedGene, knowledgeMatched, knowledgeClinical);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<StructuralVariantDTO> listCaseStructuralVariantsByGene(String caseId,
                                                                       String geneSymbol,
                                                                       String chromosome,
                                                                       Long startBp,
                                                                       Long endBp,
                                                                       Integer page,
                                                                       Integer pageSize) {
        CaseDataset dataset = resolveCase(caseId);
        if (dataset == null) {
            return Collections.emptyList();
        }
        String normalizedGene = normalizeGeneSymbol(geneSymbol);
        if (!StringUtils.hasText(normalizedGene)) {
            return Collections.emptyList();
        }
        Filter filter = buildFilter(chromosome, startBp, endBp);
        List<CaseVariantRecord> scoped = filterCaseVariants(dataset.structuralByChromosome, filter);
        List<CaseVariantRecord> geneScoped = filterByGeneSymbol(scoped, normalizedGene);
        List<CaseVariantRecord> paged = paginate(geneScoped, page, pageSize);
        if (paged.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, String> knowledgeClinicalByKey = queryKnowledgeStructuralClinical(paged);
        return paged.stream()
                .map(row -> {
                    String key = buildStructuralMatchKey(row);
                    boolean knowledgeMatched = StringUtils.hasText(key) && knowledgeClinicalByKey.containsKey(key);
                    String knowledgeClinical = knowledgeClinicalByKey.get(key);
                    return toStructuralVariant(row, normalizedGene, knowledgeMatched, knowledgeClinical);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<CaseBandOverlapDTO> listCaseOverlapBandsByChromosome(String caseId, String chromosome) {
        CaseDataset dataset = resolveCase(caseId);
        if (dataset == null) {
            return Collections.emptyList();
        }
        String normalizedChr = CoordinateUtils.normalizeChromosome(chromosome);
        if (!StringUtils.hasText(normalizedChr)) {
            return Collections.emptyList();
        }
        List<CytobandDTO> bands = variantOverviewMapper.listBandsByChromosome(normalizedChr);
        if (bands == null || bands.isEmpty()) {
            return Collections.emptyList();
        }

        List<CaseVariantRecord> caseSmallRows = dataset.smallByChromosome.getOrDefault(normalizedChr, Collections.emptyList());
        List<CaseVariantRecord> caseStructuralRows = dataset.structuralByChromosome.getOrDefault(normalizedChr, Collections.emptyList());
        if ((caseSmallRows == null || caseSmallRows.isEmpty()) && (caseStructuralRows == null || caseStructuralRows.isEmpty())) {
            return Collections.emptyList();
        }

        Map<String, String> knowledgeSmallByKey = queryKnowledgeSmallClinical(caseSmallRows);
        Map<String, String> knowledgeStructuralByKey = queryKnowledgeStructuralClinical(caseStructuralRows);
        Map<Long, Set<String>> overlapVariantIdsByBandId = new HashMap<Long, Set<String>>();
        Map<Long, CaseVariantRecord> overlapAnchorByBandId = new HashMap<Long, CaseVariantRecord>();
        Map<Long, String> overlapAnchorClinicalByBandId = new HashMap<Long, String>();

        if (caseSmallRows != null && !caseSmallRows.isEmpty() && !knowledgeSmallByKey.isEmpty()) {
            for (CaseVariantRecord row : caseSmallRows) {
                String key = buildSmallMatchKey(row);
                if (!StringUtils.hasText(key) || !knowledgeSmallByKey.containsKey(key)) {
                    continue;
                }
                long position = row.startBp;
                for (CytobandDTO band : bands) {
                    Long bandId = band == null ? null : band.getBandId();
                    Long bandStart = band == null ? null : band.getStartBp();
                    Long bandEnd = band == null ? null : band.getEndBp();
                    if (bandId == null || bandStart == null || bandEnd == null) {
                        continue;
                    }
                    if (position < bandStart || position > bandEnd) {
                        continue;
                    }
                    overlapVariantIdsByBandId
                            .computeIfAbsent(bandId, id -> new LinkedHashSet<String>())
                            .add(row.variantId);
                    updateOverlapAnchor(
                            overlapAnchorByBandId,
                            overlapAnchorClinicalByBandId,
                            bandId,
                            row,
                            key,
                            knowledgeSmallByKey.get(key)
                    );
                }
            }
        }

        if (caseStructuralRows != null && !caseStructuralRows.isEmpty() && !knowledgeStructuralByKey.isEmpty()) {
            for (CaseVariantRecord row : caseStructuralRows) {
                String key = buildStructuralMatchKey(row);
                if (!StringUtils.hasText(key) || !knowledgeStructuralByKey.containsKey(key)) {
                    continue;
                }
                for (CytobandDTO band : bands) {
                    Long bandId = band == null ? null : band.getBandId();
                    Long bandStart = band == null ? null : band.getStartBp();
                    Long bandEnd = band == null ? null : band.getEndBp();
                    if (bandId == null || bandStart == null || bandEnd == null) {
                        continue;
                    }
                    if (!CoordinateUtils.overlaps(row.startBp, row.endBp, bandStart, bandEnd)) {
                        continue;
                    }
                    overlapVariantIdsByBandId
                            .computeIfAbsent(bandId, id -> new LinkedHashSet<String>())
                            .add(row.variantId);
                    updateOverlapAnchor(
                            overlapAnchorByBandId,
                            overlapAnchorClinicalByBandId,
                            bandId,
                            row,
                            key,
                            knowledgeStructuralByKey.get(key)
                    );
                }
            }
        }

        if (overlapVariantIdsByBandId.isEmpty()) {
            return Collections.emptyList();
        }

        List<CaseBandOverlapDTO> result = new ArrayList<CaseBandOverlapDTO>();
        for (CytobandDTO band : bands) {
            if (band == null || band.getBandId() == null) {
                continue;
            }
            Set<String> matchedVariantIds = overlapVariantIdsByBandId.get(band.getBandId());
            if (matchedVariantIds == null || matchedVariantIds.isEmpty()) {
                continue;
            }
            CaseBandOverlapDTO dto = new CaseBandOverlapDTO();
            dto.setBandId(band.getBandId());
            dto.setChromosome(band.getChromosome());
            dto.setBandName(band.getBandName());
            dto.setStartBp(band.getStartBp());
            dto.setEndBp(band.getEndBp());
            dto.setOverlapCount(matchedVariantIds.size());
            CaseVariantRecord anchor = overlapAnchorByBandId.get(band.getBandId());
            if (anchor != null) {
                String exactKey = anchor.structural
                        ? buildStructuralMatchKey(anchor)
                        : buildSmallMatchKey(anchor);
                dto.setAnchorVariantId(anchor.variantId);
                dto.setAnchorGeneSymbol(anchor.primaryGeneSymbol);
                dto.setAnchorStartBp(anchor.startBp);
                dto.setAnchorEndBp(anchor.endBp);
                dto.setAnchorRef(anchor.ref);
                dto.setAnchorAlt(anchor.alt);
                dto.setAnchorVariantType(anchor.variantType);
                dto.setAnchorExactKey(exactKey);
                dto.setAnchorKnowledgeClinicalSignificance(
                        overlapAnchorClinicalByBandId.get(band.getBandId())
                );
            }
            result.add(dto);
        }
        return result;
    }

    @Override
    public List<CaseMatchedVariantDTO> listCaseMatchedVariants(String caseId,
                                                               String chromosome,
                                                               Integer page,
                                                               Integer pageSize) {
        CaseDataset dataset = resolveCase(caseId);
        if (dataset == null) {
            return Collections.emptyList();
        }
        String normalizedChr = CoordinateUtils.normalizeChromosome(chromosome);

        List<CaseVariantRecord> caseSmallRows = collectCaseVariants(dataset.smallByChromosome, normalizedChr);
        List<CaseVariantRecord> caseStructuralRows = collectCaseVariants(dataset.structuralByChromosome, normalizedChr);
        if ((caseSmallRows == null || caseSmallRows.isEmpty()) && (caseStructuralRows == null || caseStructuralRows.isEmpty())) {
            return Collections.emptyList();
        }

        Map<String, String> knowledgeSmallByKey = queryKnowledgeSmallClinical(caseSmallRows);
        Map<String, String> knowledgeStructuralByKey = queryKnowledgeStructuralClinical(caseStructuralRows);
        if (knowledgeSmallByKey.isEmpty() && knowledgeStructuralByKey.isEmpty()) {
            return Collections.emptyList();
        }

        List<CaseMatchedVariantDTO> matched = new ArrayList<CaseMatchedVariantDTO>();
        if (caseSmallRows != null && !caseSmallRows.isEmpty() && !knowledgeSmallByKey.isEmpty()) {
            for (CaseVariantRecord row : caseSmallRows) {
                String key = buildSmallMatchKey(row);
                if (!StringUtils.hasText(key) || !knowledgeSmallByKey.containsKey(key)) {
                    continue;
                }
                matched.add(toCaseMatchedVariant(row, "small_variant", key, knowledgeSmallByKey.get(key)));
            }
        }
        if (caseStructuralRows != null && !caseStructuralRows.isEmpty() && !knowledgeStructuralByKey.isEmpty()) {
            for (CaseVariantRecord row : caseStructuralRows) {
                String key = buildStructuralMatchKey(row);
                if (!StringUtils.hasText(key) || !knowledgeStructuralByKey.containsKey(key)) {
                    continue;
                }
                matched.add(toCaseMatchedVariant(row, "structural_variant", key, knowledgeStructuralByKey.get(key)));
            }
        }
        if (matched.isEmpty()) {
            return Collections.emptyList();
        }

        matched.sort(
                Comparator
                        .comparing((CaseMatchedVariantDTO item) -> normalizeChromosomeOrder(item.getChromosome()))
                        .thenComparing(item -> item.getStartBp() == null ? Long.MAX_VALUE : item.getStartBp())
                        .thenComparing(item -> item.getEndBp() == null ? Long.MAX_VALUE : item.getEndBp())
                        .thenComparing(item -> item.getVariantId() == null ? "" : item.getVariantId())
        );

        return paginateAny(matched, page, pageSize);
    }

    private void updateOverlapAnchor(Map<Long, CaseVariantRecord> anchorByBandId,
                                     Map<Long, String> anchorClinicalByBandId,
                                     Long bandId,
                                     CaseVariantRecord candidate,
                                     String candidateExactKey,
                                     String candidateClinical) {
        if (bandId == null || candidate == null || !StringUtils.hasText(candidateExactKey)) {
            return;
        }
        CaseVariantRecord current = anchorByBandId.get(bandId);
        if (current == null || isCoordinateEarlier(current, candidate)) {
            anchorByBandId.put(bandId, candidate);
            anchorClinicalByBandId.put(bandId, candidateClinical);
        }
    }

    private boolean isCoordinateEarlier(CaseVariantRecord current, CaseVariantRecord candidate) {
        if (current == null) {
            return true;
        }
        if (candidate == null) {
            return false;
        }
        if (candidate.startBp != current.startBp) {
            return candidate.startBp < current.startBp;
        }
        if (candidate.endBp != current.endBp) {
            return candidate.endBp < current.endBp;
        }
        String currentVariantId = current.variantId == null ? "" : current.variantId;
        String candidateVariantId = candidate.variantId == null ? "" : candidate.variantId;
        return candidateVariantId.compareTo(currentVariantId) < 0;
    }

    private CaseDataset resolveCase(String caseId) {
        String key = normalizeCaseId(caseId);
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return caseStore.get(key);
    }

    private CaseDataset parseVcf(String caseId, String fileName, String genomeAssembly, Path vcfPath) {
        List<CaseVariantRecord> smallVariants = new ArrayList<CaseVariantRecord>();
        List<CaseVariantRecord> structuralVariants = new ArrayList<CaseVariantRecord>();
        String sampleName = null;
        int sequence = 0;

        try (VCFFileReader reader = new VCFFileReader(vcfPath.toFile(), false)) {
            VCFHeader header = reader.getFileHeader();
            sampleName = VcfUploadUtils.requireSingleSample(header);
            for (VariantContext variantContext : reader) {
                sequence += 1;
                CaseVariantRecord row = toCaseVariantRecord(variantContext, sampleName, sequence);
                if (row == null) {
                    continue;
                }
                if (row.structural) {
                    structuralVariants.add(row);
                } else {
                    smallVariants.add(row);
                }
            }
        } catch (InvalidVcfUploadException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new InvalidVcfUploadException("VCF or BCF file could not be parsed", ex);
        }

        Comparator<CaseVariantRecord> sorter = Comparator
                .comparing((CaseVariantRecord item) -> item.chromosomeOrder)
                .thenComparingLong(item -> item.startBp)
                .thenComparing(item -> item.variantId);
        smallVariants.sort(sorter);
        structuralVariants.sort(sorter);

        return new CaseDataset(
                caseId,
                fileName,
                sampleName,
                genomeAssembly,
                System.currentTimeMillis(),
                buildByChromosomeIndex(smallVariants),
                buildByChromosomeIndex(structuralVariants)
        );
    }

    private Map<String, List<CaseVariantRecord>> buildByChromosomeIndex(List<CaseVariantRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<CaseVariantRecord>> grouped = new HashMap<String, List<CaseVariantRecord>>();
        for (CaseVariantRecord row : rows) {
            List<CaseVariantRecord> list = grouped.computeIfAbsent(row.chromosome, key -> new ArrayList<CaseVariantRecord>());
            list.add(row);
        }
        grouped.replaceAll((key, value) -> Collections.unmodifiableList(value));
        return Collections.unmodifiableMap(grouped);
    }

    private CaseVariantRecord toCaseVariantRecord(VariantContext variantContext,
                                                  String sampleName,
                                                  int sequence) {
        if (variantContext == null) {
            return null;
        }
        String chromosome = CoordinateUtils.normalizeChromosome(variantContext.getContig());
        if (!StringUtils.hasText(chromosome)) {
            return null;
        }
        long startBp = variantContext.getStart();
        long endBp = Math.max(startBp, variantContext.getEnd());
        String ref = sanitizeAllele(variantContext.getReference());

        Genotype genotype = resolveGenotype(variantContext, sampleName);
        String genotypeText = genotype == null ? null : genotype.getGenotypeString(false);
        String alt = resolvePreferredAlt(variantContext, genotype);
        String variantType = resolveVariantType(variantContext, ref, alt);
        String clinicalSignificance = resolveClinicalSignificance(variantContext);
        boolean structural = isStructuralVariant(variantContext, ref, alt, startBp, endBp);
        List<String> geneSymbols = resolveGeneSymbols(variantContext);

        String variantId = chromosome
                + ":" + startBp
                + ":" + (StringUtils.hasText(ref) ? ref : "N")
                + ">" + (StringUtils.hasText(alt) ? alt : "N")
                + ":" + sequence;

        return new CaseVariantRecord(
                variantId,
                chromosome,
                normalizeChromosomeOrder(chromosome),
                startBp,
                endBp,
                ref,
                alt,
                genotypeText,
                variantType,
                clinicalSignificance,
                geneSymbols,
                structural
        );
    }

    private Genotype resolveGenotype(VariantContext variantContext, String sampleName) {
        if (variantContext == null) {
            return null;
        }
        if (StringUtils.hasText(sampleName) && variantContext.hasGenotype(sampleName)) {
            return variantContext.getGenotype(sampleName);
        }
        if (variantContext.getNSamples() > 0) {
            return variantContext.getGenotype(0);
        }
        return null;
    }

    private String resolvePreferredAlt(VariantContext variantContext, Genotype genotype) {
        if (genotype != null && genotype.isCalled() && genotype.getAlleles() != null) {
            for (Allele allele : genotype.getAlleles()) {
                if (allele == null || allele.isNoCall() || allele.isReference()) {
                    continue;
                }
                String resolved = sanitizeAllele(allele);
                if (StringUtils.hasText(resolved)) {
                    return resolved;
                }
            }
        }
        List<Allele> alternates = variantContext == null ? Collections.emptyList() : variantContext.getAlternateAlleles();
        if (alternates == null || alternates.isEmpty()) {
            return null;
        }
        for (Allele allele : alternates) {
            String resolved = sanitizeAllele(allele);
            if (StringUtils.hasText(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    private String sanitizeAllele(Allele allele) {
        if (allele == null) {
            return null;
        }
        return sanitizeAllele(allele.getDisplayString());
    }

    private String sanitizeAllele(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (".".equals(trimmed) || "*".equals(trimmed)) {
            return null;
        }
        return trimmed.toUpperCase();
    }

    private String resolveVariantType(VariantContext variantContext, String ref, String alt) {
        if (variantContext == null) {
            return "sequence variant";
        }
        if (variantContext.isSNP()) {
            return "single nucleotide variant";
        }
        if (variantContext.isMNP()) {
            return "multiple nucleotide variant";
        }
        if (variantContext.isSimpleInsertion()) {
            return "insertion";
        }
        if (variantContext.isSimpleDeletion()) {
            return "deletion";
        }
        if (variantContext.isIndel()) {
            return "insertion/deletion";
        }
        if (variantContext.isMixed()) {
            return "mixed variant";
        }
        if (variantContext.isSymbolicOrSV()) {
            String svType = variantContext.getAttributeAsString("SVTYPE", null);
            if (StringUtils.hasText(svType)) {
                return svType.trim().toLowerCase();
            }
            return "structural variant";
        }
        if (StringUtils.hasText(ref) && StringUtils.hasText(alt)) {
            int diff = Math.abs(alt.length() - ref.length());
            if (diff > 0) {
                return "insertion/deletion";
            }
        }
        return "sequence variant";
    }

    private String resolveClinicalSignificance(VariantContext variantContext) {
        if (variantContext == null) {
            return DEFAULT_CLINICAL;
        }
        String clnSig = firstNonBlank(
                variantContext.getAttributeAsString("CLNSIG", null),
                variantContext.getAttributeAsString("CLIN_SIG", null),
                variantContext.getAttributeAsString("clinical_significance", null)
        );
        if (!StringUtils.hasText(clnSig)) {
            return DEFAULT_CLINICAL;
        }
        return clnSig.replace('_', ' ').trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private List<String> resolveGeneSymbols(VariantContext variantContext) {
        if (variantContext == null) {
            return Collections.emptyList();
        }
        Set<String> symbols = new LinkedHashSet<String>();
        addGeneTokens(symbols, variantContext.getAttributeAsString("GENE", null));
        addGeneTokens(symbols, variantContext.getAttributeAsString("GENE_SYMBOL", null));
        addGeneTokens(symbols, variantContext.getAttributeAsString("SYMBOL", null));
        addGeneInfoTokens(symbols, variantContext.getAttributeAsString("GENEINFO", null));
        addAnnTokens(symbols, variantContext.getAttributeAsString("ANN", null), 3);
        addAnnTokens(symbols, variantContext.getAttributeAsString("CSQ", null), 3);
        return symbols.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>(symbols);
    }

    private void addGeneInfoTokens(Set<String> symbols, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String[] items = value.split("[,;]");
        for (String item : items) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String token = item.split(":")[0];
            addGeneTokens(symbols, token);
        }
    }

    private void addAnnTokens(Set<String> symbols, String value, int geneIndex) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String[] entries = value.split(",");
        for (String entry : entries) {
            if (!StringUtils.hasText(entry)) {
                continue;
            }
            String[] fields = entry.split("\\|");
            if (fields.length > geneIndex) {
                addGeneTokens(symbols, fields[geneIndex]);
            }
        }
    }

    private void addGeneTokens(Set<String> symbols, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String[] tokens = value.split("[,;|/\\s]+");
        for (String token : tokens) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            String normalized = token.trim().toUpperCase();
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            if ("NA".equals(normalized) || ".".equals(normalized) || "N/A".equals(normalized)) {
                continue;
            }
            symbols.add(normalized);
        }
    }

    private boolean isStructuralVariant(VariantContext variantContext,
                                        String ref,
                                        String alt,
                                        long startBp,
                                        long endBp) {
        if (variantContext != null && variantContext.isSymbolicOrSV()) {
            return true;
        }
        long span = Math.max(1L, endBp - startBp + 1L);
        if (span >= STRUCTURAL_SIZE_THRESHOLD) {
            return true;
        }
        int refLen = StringUtils.hasText(ref) ? ref.length() : 0;
        int altLen = StringUtils.hasText(alt) ? alt.length() : 0;
        int diff = Math.abs(altLen - refLen);
        return diff >= STRUCTURAL_SIZE_THRESHOLD;
    }

    private List<CaseVariantRecord> filterCaseVariants(Map<String, List<CaseVariantRecord>> byChromosome,
                                                       Filter filter) {
        if (byChromosome == null || byChromosome.isEmpty()) {
            return Collections.emptyList();
        }
        String chromosome = filter.chromosome;
        if (!StringUtils.hasText(chromosome)) {
            return Collections.emptyList();
        }
        List<CaseVariantRecord> rows = byChromosome.get(chromosome);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        if (!filter.hasRange) {
            return rows;
        }
        long start = filter.startBp;
        long end = filter.endBp;
        return rows.stream()
                .filter(item -> CoordinateUtils.overlaps(item.startBp, item.endBp, start, end))
                .collect(Collectors.toList());
    }

    private List<CaseVariantRecord> filterByGeneSymbol(List<CaseVariantRecord> rows, String geneSymbol) {
        if (!StringUtils.hasText(geneSymbol) || rows == null || rows.isEmpty()) {
            return rows == null ? Collections.<CaseVariantRecord>emptyList() : rows;
        }
        List<CaseVariantRecord> matched = rows.stream()
                .filter(item -> item != null && item.geneSymbols != null && item.geneSymbols.contains(geneSymbol))
                .collect(Collectors.toList());
        if (!matched.isEmpty()) {
            return matched;
        }
        // Keep range-scoped fallback when VCF has no gene annotations.
        return rows;
    }

    private List<CaseVariantRecord> paginate(List<CaseVariantRecord> rows, Integer page, Integer pageSize) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        int safePage = page == null ? DEFAULT_PAGE : Math.max(page, 1);
        int safePageSize = pageSize == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        int offset = (safePage - 1) * safePageSize;
        if (offset >= rows.size()) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(rows.size(), offset + safePageSize);
        return rows.subList(offset, toIndex);
    }

    private <T> List<T> paginateAny(List<T> rows, Integer page, Integer pageSize) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        int safePage = page == null ? DEFAULT_PAGE : Math.max(page, 1);
        int safePageSize = pageSize == null ? DEFAULT_PAGE_SIZE : Math.max(1, Math.min(pageSize, MAX_PAGE_SIZE));
        int offset = (safePage - 1) * safePageSize;
        if (offset >= rows.size()) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(rows.size(), offset + safePageSize);
        return rows.subList(offset, toIndex);
    }

    private List<CaseVariantRecord> collectCaseVariants(Map<String, List<CaseVariantRecord>> byChromosome,
                                                        String normalizedChromosome) {
        if (byChromosome == null || byChromosome.isEmpty()) {
            return Collections.emptyList();
        }
        if (StringUtils.hasText(normalizedChromosome)) {
            List<CaseVariantRecord> rows = byChromosome.get(normalizedChromosome);
            return rows == null ? Collections.<CaseVariantRecord>emptyList() : rows;
        }
        List<CaseVariantRecord> rows = new ArrayList<CaseVariantRecord>();
        for (List<CaseVariantRecord> chunk : byChromosome.values()) {
            if (chunk == null || chunk.isEmpty()) continue;
            rows.addAll(chunk);
        }
        return rows;
    }

    private Filter buildFilter(String chromosome, Long startBp, Long endBp) {
        String normalizedChr = CoordinateUtils.normalizeChromosome(chromosome);
        boolean hasStart = startBp != null;
        boolean hasEnd = endBp != null;
        if (!hasStart || !hasEnd) {
            return new Filter(normalizedChr, 0L, 0L, false);
        }
        long[] range = CoordinateUtils.normalizeRange(startBp, endBp);
        return new Filter(normalizedChr, range[0], range[1], true);
    }

    private String normalizeCaseId(String caseId) {
        return caseId == null ? null : caseId.trim();
    }

    private String normalizeGeneSymbol(String geneSymbol) {
        if (!StringUtils.hasText(geneSymbol)) {
            return null;
        }
        return geneSymbol.trim().toUpperCase();
    }

    private int normalizeChromosomeOrder(String chromosome) {
        if (!StringUtils.hasText(chromosome)) {
            return Integer.MAX_VALUE;
        }
        String normalized = chromosome.trim().toUpperCase();
        try {
            int value = Integer.parseInt(normalized);
            return value;
        } catch (NumberFormatException ignored) {
            // ignore
        }
        if ("X".equals(normalized)) return 23;
        if ("Y".equals(normalized)) return 24;
        if ("MT".equals(normalized) || "M".equals(normalized)) return 25;
        return Integer.MAX_VALUE;
    }

    private Map<String, String> queryKnowledgeSmallClinical(List<CaseVariantRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> matchedClinicalByKey = new HashMap<String, String>();
        Map<String, Set<String>> keysByChromosome = new HashMap<String, Set<String>>();
        Map<String, Set<Long>> positionsByChromosome = new HashMap<String, Set<Long>>();

        for (CaseVariantRecord row : rows) {
            if (row == null || !StringUtils.hasText(row.chromosome)) {
                continue;
            }
            String chromosome = CoordinateUtils.normalizeChromosome(row.chromosome);
            String key = buildSmallMatchKey(row);
            if (!StringUtils.hasText(chromosome) || !StringUtils.hasText(key)) {
                continue;
            }
            keysByChromosome
                    .computeIfAbsent(chromosome, ignored -> new LinkedHashSet<String>())
                    .add(key);
            positionsByChromosome
                    .computeIfAbsent(chromosome, ignored -> new LinkedHashSet<Long>())
                    .add(row.startBp);
        }

        String sql = "SELECT chr.chr_name AS chromosome, " +
                "sv.start_bp AS position, " +
                "sv.ref_allele AS ref_allele, " +
                "sv.alt_allele AS alt_allele, " +
                "sv.clinical_significance AS clinical_significance " +
                "FROM small_variant sv " +
                "JOIN chromosome chr ON chr.chromosome_id = sv.chromosome_id " +
                "WHERE chr.chr_name = :chromosome " +
                "AND sv.start_bp IN (:positions)";

        for (Map.Entry<String, Set<String>> entry : keysByChromosome.entrySet()) {
            String chromosome = CoordinateUtils.normalizeChromosome(entry.getKey());
            if (!StringUtils.hasText(chromosome)) {
                continue;
            }
            Set<String> expectedKeys = entry.getValue();
            if (expectedKeys == null || expectedKeys.isEmpty()) {
                continue;
            }
            Set<Long> positions = positionsByChromosome.getOrDefault(chromosome, Collections.emptySet());
            if (positions.isEmpty()) {
                continue;
            }
            Set<String> resolvedKeys = new LinkedHashSet<String>();
            List<Long> positionList = new ArrayList<Long>(positions);
            List<List<Long>> positionBatches = partitionList(positionList, KNOWLEDGE_MATCH_BATCH_SIZE);
            for (List<Long> batch : positionBatches) {
                if (batch == null || batch.isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> dbRows;
                try {
                    MapSqlParameterSource params = new MapSqlParameterSource()
                            .addValue("chromosome", chromosome)
                            .addValue("positions", batch);
                    dbRows = varvisNamedJdbcTemplate.queryForList(sql, params);
                } catch (Exception ex) {
                    LOGGER.warn("queryKnowledgeSmallClinical failed on chromosome={}, batchSize={}", chromosome, batch.size(), ex);
                    continue;
                }
                for (Map<String, Object> dbRow : dbRows) {
                    String key = buildSmallMatchKey(
                            stringValue(dbRow.get("chromosome")),
                            longValue(dbRow.get("position")),
                            stringValue(dbRow.get("ref_allele")),
                            stringValue(dbRow.get("alt_allele"))
                    );
                    if (!StringUtils.hasText(key) || !expectedKeys.contains(key)) {
                        continue;
                    }
                    String clinical = normalizeKnowledgeClinical(stringValue(dbRow.get("clinical_significance")));
                    matchedClinicalByKey.putIfAbsent(key, clinical);
                    resolvedKeys.add(key);
                }
                if (resolvedKeys.size() >= expectedKeys.size()) {
                    break;
                }
            }
        }
        return matchedClinicalByKey;
    }

    private Map<String, String> queryKnowledgeStructuralClinical(List<CaseVariantRecord> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> matchedClinicalByKey = new HashMap<String, String>();
        Map<String, Set<String>> keysByChromosome = new HashMap<String, Set<String>>();
        Map<String, Set<Long>> startsByChromosome = new HashMap<String, Set<Long>>();

        for (CaseVariantRecord row : rows) {
            if (row == null || !StringUtils.hasText(row.chromosome)) {
                continue;
            }
            String chromosome = CoordinateUtils.normalizeChromosome(row.chromosome);
            String key = buildStructuralMatchKey(row);
            if (!StringUtils.hasText(chromosome) || !StringUtils.hasText(key)) {
                continue;
            }
            keysByChromosome
                    .computeIfAbsent(chromosome, ignored -> new LinkedHashSet<String>())
                    .add(key);
            startsByChromosome
                    .computeIfAbsent(chromosome, ignored -> new LinkedHashSet<Long>())
                    .add(row.startBp);
        }

        String sql = "SELECT chr.chr_name AS chromosome, " +
                "stv.start_bp AS start_bp, " +
                "stv.end_bp AS end_bp, " +
                "stv.sv_type AS sv_type, " +
                "stv.clinical_significance AS clinical_significance " +
                "FROM structural_variant stv " +
                "JOIN chromosome chr ON chr.chromosome_id = stv.chromosome_id " +
                "WHERE chr.chr_name = :chromosome " +
                "AND stv.start_bp IN (:startBps)";

        for (Map.Entry<String, Set<String>> entry : keysByChromosome.entrySet()) {
            String chromosome = CoordinateUtils.normalizeChromosome(entry.getKey());
            if (!StringUtils.hasText(chromosome)) {
                continue;
            }
            Set<String> expectedKeys = entry.getValue();
            if (expectedKeys == null || expectedKeys.isEmpty()) {
                continue;
            }
            Set<Long> starts = startsByChromosome.getOrDefault(chromosome, Collections.emptySet());
            if (starts.isEmpty()) {
                continue;
            }
            Set<String> resolvedKeys = new LinkedHashSet<String>();
            List<Long> startList = new ArrayList<Long>(starts);
            List<List<Long>> startBatches = partitionList(startList, KNOWLEDGE_MATCH_BATCH_SIZE);
            for (List<Long> batch : startBatches) {
                if (batch == null || batch.isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> dbRows;
                try {
                    MapSqlParameterSource params = new MapSqlParameterSource()
                            .addValue("chromosome", chromosome)
                            .addValue("startBps", batch);
                    dbRows = varvisNamedJdbcTemplate.queryForList(sql, params);
                } catch (Exception ex) {
                    LOGGER.warn("queryKnowledgeStructuralClinical failed on chromosome={}, batchSize={}", chromosome, batch.size(), ex);
                    continue;
                }
                for (Map<String, Object> dbRow : dbRows) {
                    String key = buildStructuralMatchKey(
                            stringValue(dbRow.get("chromosome")),
                            longValue(dbRow.get("start_bp")),
                            longValue(dbRow.get("end_bp")),
                            stringValue(dbRow.get("sv_type"))
                    );
                    if (!StringUtils.hasText(key) || !expectedKeys.contains(key)) {
                        continue;
                    }
                    String clinical = normalizeKnowledgeClinical(stringValue(dbRow.get("clinical_significance")));
                    matchedClinicalByKey.putIfAbsent(key, clinical);
                    resolvedKeys.add(key);
                }
                if (resolvedKeys.size() >= expectedKeys.size()) {
                    break;
                }
            }
        }
        return matchedClinicalByKey;
    }

    private <T> List<List<T>> partitionList(List<T> items, int batchSize) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        int safeBatchSize = Math.max(1, batchSize);
        List<List<T>> batches = new ArrayList<List<T>>();
        for (int i = 0; i < items.size(); i += safeBatchSize) {
            int end = Math.min(items.size(), i + safeBatchSize);
            batches.add(items.subList(i, end));
        }
        return batches;
    }

    private String buildSmallMatchKey(CaseVariantRecord row) {
        if (row == null) {
            return null;
        }
        return buildSmallMatchKey(row.chromosome, row.startBp, row.ref, row.alt);
    }

    private String buildSmallMatchKey(String chromosome, Long position, String ref, String alt) {
        String normalizedChr = CoordinateUtils.normalizeChromosome(chromosome);
        if (!StringUtils.hasText(normalizedChr) || position == null) {
            return null;
        }
        return normalizedChr + ":"
                + position + ":"
                + normalizeMatchAllele(ref) + ":"
                + normalizeMatchAllele(alt);
    }

    private String buildStructuralMatchKey(CaseVariantRecord row) {
        if (row == null) {
            return null;
        }
        return buildStructuralMatchKey(row.chromosome, row.startBp, row.endBp, row.variantType);
    }

    private String buildStructuralMatchKey(String chromosome, Long startBp, Long endBp, String svType) {
        String normalizedChr = CoordinateUtils.normalizeChromosome(chromosome);
        if (!StringUtils.hasText(normalizedChr) || startBp == null || endBp == null) {
            return null;
        }
        return normalizedChr + ":"
                + startBp + ":"
                + endBp + ":"
                + normalizeStructuralTypeForMatch(svType);
    }

    private String normalizeMatchAllele(String value) {
        String normalized = sanitizeAllele(value);
        return normalized == null ? "" : normalized;
    }

    private String normalizeStructuralTypeForMatch(String svType) {
        if (!StringUtils.hasText(svType)) {
            return "";
        }
        return svType.trim().toUpperCase().replaceAll("\\s+", "_");
    }

    private String normalizeKnowledgeClinical(String clinical) {
        if (!StringUtils.hasText(clinical)) {
            return null;
        }
        return clinical.trim().replace('_', ' ');
    }

    private String resolveClinicalDisplay(String caseClinical, String knowledgeClinical) {
        if (!isUnknownClinical(caseClinical)) {
            return caseClinical;
        }
        if (StringUtils.hasText(knowledgeClinical)) {
            return knowledgeClinical;
        }
        return DEFAULT_CLINICAL;
    }

    private boolean isUnknownClinical(String clinical) {
        if (!StringUtils.hasText(clinical)) {
            return true;
        }
        String normalized = clinical.trim().toLowerCase();
        return DEFAULT_CLINICAL.equalsIgnoreCase(normalized)
                || UNKNOWN_CLINICAL.equals(normalized)
                || "-".equals(normalized)
                || "--".equals(normalized);
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private CaseMatchedVariantDTO toCaseMatchedVariant(CaseVariantRecord row,
                                                       String variantClass,
                                                       String exactKey,
                                                       String knowledgeClinicalSignificance) {
        CaseMatchedVariantDTO dto = new CaseMatchedVariantDTO();
        dto.setVariantId(row.variantId);
        dto.setChromosome(row.chromosome);
        dto.setPosition(row.structural ? null : row.startBp);
        dto.setStartBp(row.startBp);
        dto.setEndBp(row.endBp);
        dto.setRef(row.ref);
        dto.setAlt(row.alt);
        dto.setGeneSymbol(row.primaryGeneSymbol);
        dto.setVariantType(row.variantType);
        dto.setVariantClass(variantClass);
        dto.setKnowledgeClinicalSignificance(knowledgeClinicalSignificance);
        dto.setClinicalSignificance(resolveClinicalDisplay(row.clinicalSignificance, knowledgeClinicalSignificance));
        dto.setExactKey(exactKey);
        return dto;
    }

    private SmallVariantDTO toSmallVariant(CaseVariantRecord row,
                                           String geneSymbol,
                                           Boolean knowledgeMatched,
                                           String knowledgeClinicalSignificance) {
        SmallVariantDTO dto = new SmallVariantDTO();
        dto.setVariantId(row.variantId);
        dto.setChromosome(row.chromosome);
        dto.setPosition(row.startBp);
        dto.setRef(row.ref);
        dto.setAlt(row.alt);
        dto.setGeneSymbol(StringUtils.hasText(geneSymbol) ? geneSymbol : row.primaryGeneSymbol);
        dto.setVariantType(row.variantType);
        dto.setClinicalSignificance(resolveClinicalDisplay(row.clinicalSignificance, knowledgeClinicalSignificance));
        dto.setKnowledgeMatched(knowledgeMatched);
        dto.setPatientOnly(knowledgeMatched == null ? null : !knowledgeMatched);
        dto.setKnowledgeClinicalSignificance(knowledgeClinicalSignificance);
        dto.setGenotype(row.genotype);
        dto.setHgvsC(null);
        dto.setHgvsP(null);
        return dto;
    }

    private StructuralVariantDTO toStructuralVariant(CaseVariantRecord row,
                                                     String geneSymbol,
                                                     Boolean knowledgeMatched,
                                                     String knowledgeClinicalSignificance) {
        StructuralVariantDTO dto = new StructuralVariantDTO();
        dto.setVariantId(row.variantId);
        dto.setChromosome(row.chromosome);
        dto.setStartBp(row.startBp);
        dto.setEndBp(row.endBp);
        dto.setSvType(row.variantType);
        dto.setCopyNumber(null);
        dto.setGeneSymbol(StringUtils.hasText(geneSymbol) ? geneSymbol : row.primaryGeneSymbol);
        dto.setClinicalSignificance(resolveClinicalDisplay(row.clinicalSignificance, knowledgeClinicalSignificance));
        dto.setKnowledgeMatched(knowledgeMatched);
        dto.setPatientOnly(knowledgeMatched == null ? null : !knowledgeMatched);
        dto.setKnowledgeClinicalSignificance(knowledgeClinicalSignificance);
        return dto;
    }

    private CaseVcfUploadResponseDTO toUploadResponse(CaseDataset dataset) {
        int smallCount = dataset.smallByChromosome.values().stream()
                .mapToInt(records -> records == null ? 0 : records.size())
                .sum();
        int structuralCount = dataset.structuralByChromosome.values().stream()
                .mapToInt(records -> records == null ? 0 : records.size())
                .sum();
        CaseVcfUploadResponseDTO dto = new CaseVcfUploadResponseDTO();
        dto.setCaseId(dataset.caseId);
        dto.setFileName(dataset.fileName);
        dto.setSampleName(dataset.sampleName);
        dto.setGenomeAssembly(dataset.genomeAssembly);
        dto.setSmallVariantCount(smallCount);
        dto.setStructuralVariantCount(structuralCount);
        dto.setTotalVariants(smallCount + structuralCount);
        dto.setUploadedAtEpochMs(dataset.uploadedAtEpochMs);
        return dto;
    }

    private String requireGenomeAssembly(String genomeAssembly) {
        if (!StringUtils.hasText(genomeAssembly)) {
            throw new InvalidVcfUploadException("genomeAssembly is required; use GRCh38");
        }
        if ("GRCh38".equalsIgnoreCase(genomeAssembly)) {
            return "GRCh38";
        }
        throw new InvalidVcfUploadException("genomeAssembly must be GRCh38");
    }

    private static class Filter {
        private final String chromosome;
        private final long startBp;
        private final long endBp;
        private final boolean hasRange;

        private Filter(String chromosome, long startBp, long endBp, boolean hasRange) {
            this.chromosome = chromosome;
            this.startBp = startBp;
            this.endBp = endBp;
            this.hasRange = hasRange;
        }
    }

    private static class CaseDataset {
        private final String caseId;
        private final String fileName;
        private final String sampleName;
        private final String genomeAssembly;
        private final long uploadedAtEpochMs;
        private final Map<String, List<CaseVariantRecord>> smallByChromosome;
        private final Map<String, List<CaseVariantRecord>> structuralByChromosome;

        private CaseDataset(String caseId,
                            String fileName,
                            String sampleName,
                            String genomeAssembly,
                            long uploadedAtEpochMs,
                            Map<String, List<CaseVariantRecord>> smallByChromosome,
                            Map<String, List<CaseVariantRecord>> structuralByChromosome) {
            this.caseId = caseId;
            this.fileName = fileName;
            this.sampleName = sampleName;
            this.genomeAssembly = genomeAssembly;
            this.uploadedAtEpochMs = uploadedAtEpochMs;
            this.smallByChromosome = smallByChromosome;
            this.structuralByChromosome = structuralByChromosome;
        }
    }

    private static class CaseVariantRecord {
        private final String variantId;
        private final String chromosome;
        private final Integer chromosomeOrder;
        private final long startBp;
        private final long endBp;
        private final String ref;
        private final String alt;
        private final String genotype;
        private final String variantType;
        private final String clinicalSignificance;
        private final String primaryGeneSymbol;
        private final List<String> geneSymbols;
        private final boolean structural;

        private CaseVariantRecord(String variantId,
                                  String chromosome,
                                  Integer chromosomeOrder,
                                  long startBp,
                                  long endBp,
                                  String ref,
                                  String alt,
                                  String genotype,
                                  String variantType,
                                  String clinicalSignificance,
                                  List<String> geneSymbols,
                                  boolean structural) {
            this.variantId = variantId;
            this.chromosome = chromosome;
            this.chromosomeOrder = chromosomeOrder;
            this.startBp = startBp;
            this.endBp = endBp;
            this.ref = ref;
            this.alt = alt;
            this.genotype = genotype;
            this.variantType = variantType;
            this.clinicalSignificance = clinicalSignificance;
            this.geneSymbols = geneSymbols == null ? Collections.<String>emptyList() : geneSymbols;
            this.primaryGeneSymbol = this.geneSymbols.isEmpty() ? null : this.geneSymbols.get(0);
            this.structural = structural;
        }
    }
}
