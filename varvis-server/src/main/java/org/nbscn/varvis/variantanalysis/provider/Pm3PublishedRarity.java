package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.lang.Nullable;

/** Evaluate released rarity rules against the exact alleles in an indexed joint sites snapshot. */
final class Pm3PublishedRarity {
    static final String SCOPE = "GNOMAD_JOINT_EXOMES_GENOMES_V4_1";
    private static final List<String> ANCESTRIES = List.of("afr","ami","amr","asj","eas","fin","mid","nfe","remaining","sas");
    private static final Pattern THRESHOLD = Pattern.compile("^\\*\\*Allele frequency <(0\\.[0-9]+) \\(<0\\.[0-9]+%\\) in all populations in gnomAD\\.\\*\\*");
    private static final Pattern FAF_THRESHOLD = Pattern.compile("Absent/rare from controls in an ethnically-matched cohort population sample\\. "
            +"\\* Used if the gnomAD PopMax Filtering Allele Frequency \\(FAF\\) is ≤ ([0-9]+(?:\\.[0-9]+)?) x 10<sup>(-[0-9]+)</sup>\\.");
    private static final List<String> FAF_GROUPS = List.of("afr","amr","eas","mid","nfe","sas");
    private static final String FAF_METHOD = "JOINT_POPMAX_FAF95_INCLUSIVE";
    private static final String ROOT = "https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/vcf/joint/";
    private final ObjectMapper json;

    Pm3PublishedRarity(ObjectMapper json) { this.json = json; }

    static boolean applies(JsonNode row) {
        var match=row.at("/policySelection/matches/0");
        return match.path("independentPp4").isObject() || match.path("reviewedCaseRule").isObject();
    }

    ObjectNode qualify(JsonNode observations, JsonNode row, String role, ObjectNode context) {
        var allele = context.at("/variant/evidenceQueryAllele");
        var out = json.createObjectNode().put("schemaVersion","pm3-local-rarity-qualification-v1")
                .put("qualification","UNKNOWN").put("scope",SCOPE)
                .put("wholeGnomadConclusion",false).put("businessPublishable",false);
        out.set("queryAllele",allele.deepCopy());
        out.set("contextId",context.path("contextId"));
        try {
            var rule = threshold(observations,row);
            String chromosome = allele.path("contig").asText(), reference = allele.path("reference").asText();
            String alt = allele.path("alternate").asText();
            long position = allele.path("position").asLong();
            var snapshot = slice(observations, chromosome, position);
            var source = snapshot.path("source");
            require(source.isObject(),"PM3_PUBLISHED_JOINT_INDEXED_SOURCE_REQUIRED");
            require("GRCh38".equals(source.path("assembly").asText()) && source.path("assembly").equals(allele.path("assembly"))
                    && chromosome.matches("(?:[1-9]|1[0-9]|2[0-2])") && chromosome.equals(source.path("chromosome").asText())
                    && "gnomAD v4.1 joint sites".equals(source.path("dataset").asText())
                    && (ROOT+"gnomad.joint.v4.1.sites.chr"+chromosome+".vcf.bgz").equals(source.path("sourceUrl").asText())
                    && position >= source.path("indexedIntervalStart").asLong() && position <= source.path("indexedIntervalEnd").asLong()
                    && reference.matches("[ACGT]") && alt.matches("[ACGT]") && !reference.equals(alt)
                    && Pm3LiteratureAssessment.sameAllele(allele,row.at("/identities/"+role+"/allele")),
                    "PM3_PUBLISHED_JOINT_SOURCE_BINDING_MISMATCH");
            boolean queried = false;
            for(var p:source.path("queriedPositions"))if(p.isIntegralNumber() && p.asLong()==position)queried=true;
            require(queried && snapshot.path("retrievalSha256").asText().matches("[a-f0-9]{64}"),
                    "PM3_PUBLISHED_JOINT_EXACT_SITE_REQUIRED");
            byte[] vcf = decoded(snapshot.path("vcf"),source.path("sha256").asText(),2097152);
            List<String[]> records = new ArrayList<>();
            int rowCount=0;
            String[] lines = new String(vcf,StandardCharsets.UTF_8).split("\\R");
            boolean header=false;
            for(String line:lines) {
                if(line.startsWith("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO")) {header=true;continue;}
                if(line.startsWith("##") || line.isEmpty())continue;
                String[] c = line.split("\\t",-1);
                require(header && c.length==8 && c[0].equals("chr"+chromosome),"PM3_PUBLISHED_JOINT_VCF_INVALID");
                rowCount++;
                if(Long.toString(position).equals(c[1]))records.add(c);
            }
            require(rowCount==source.path("rowCount").asInt(-1) && records.size()==1,
                    "PM3_PUBLISHED_JOINT_UNAMBIGUOUS_CALLABLE_SITE_REQUIRED");
            var record = records.get(0);
            require(reference.equals(record[3]) && record[4].matches("[ACGT]") && !reference.equals(record[4]),
                    "PM3_PUBLISHED_JOINT_SITE_REFERENCE_OR_ALT_MISMATCH");
            require("PASS".equals(record[6]),"PM3_PUBLISHED_JOINT_SITE_NOT_PASS");
            Map<String,String> info = new HashMap<>();
            for(String item:record[7].split(";")) {
                int equal=item.indexOf('=');
                String key=equal<0?item:item.substring(0,equal);
                require(!info.containsKey(key),"PM3_PUBLISHED_JOINT_DUPLICATE_INFO");
                info.put(key,equal<0?"true":item.substring(equal+1));
            }
            boolean exact = alt.equals(record[4]);
            long totalNumber=count(info,"AN_joint"), totalCount=count(info,"AC_joint"), totalHom=count(info,"nhomalt_joint");
            long exomeNumber=count(info,"AN_exomes"), genomeNumber=count(info,"AN_genomes");
            require(totalCount>0 && totalCount<=totalNumber && totalHom<=totalCount/2
                    && exomeNumber>0 && genomeNumber>0 && exomeNumber+genomeNumber==totalNumber,
                    "PM3_PUBLISHED_JOINT_COUNT_INCONSISTENT");
            var evidence=json.createObjectNode().put("outcome",exact?"EXACT_ALLELE":"NO_RECORD")
                    .put("filter",record[6]).put("observedSiteAlternate",record[4]).put("jointCallableAlleles",totalNumber)
                    .put("exomeCallableAlleles",exomeNumber).put("genomeCallableAlleles",genomeNumber)
                    .put("outsideUkbCaptureRegion",info.containsKey("outside_ukb_capture_region"))
                    .put("outsideBroadCaptureRegion",info.containsKey("outside_broad_capture_region"));
            if(rule.faf())evidence.put("frequencyMethod",FAF_METHOD).put("inclusiveFrequencyThreshold",rule.limit().toPlainString());
            else evidence.put("strictFrequencyThreshold",rule.limit().toPlainString());
            evidence.set("source",source.deepCopy());
            evidence.set("retrievalSha256",snapshot.path("retrievalSha256"));
            var ancestry = evidence.putObject("ancestries");
            long observedNumber=0, observedCount=0, observedHom=0;
            BigDecimal maximum=BigDecimal.ZERO;
            String maximumGroup="";
            boolean within=true;
            for(String group:ANCESTRIES) {
                long number=count(info,"AN_joint_"+group), ac=count(info,"AC_joint_"+group), hom=count(info,"nhomalt_joint_"+group);
                require(number>0 && ac<=number && hom<=ac/2,"PM3_PUBLISHED_JOINT_ANCESTRY_NOT_CALLABLE");
                observedNumber+=number;observedCount+=ac;observedHom+=hom;
                var stats=ancestry.putObject(group).put("AN",number);
                if(!exact)continue;
                BigDecimal frequency=BigDecimal.valueOf(ac).divide(BigDecimal.valueOf(number),16,RoundingMode.HALF_EVEN);
                if(frequency.compareTo(maximum)>0) {maximum=frequency;maximumGroup=group;}
                within &= BigDecimal.valueOf(ac).compareTo(rule.limit().multiply(BigDecimal.valueOf(number)))<0;
                stats.put("AC",ac).put("homozygotes",hom).put("AF",frequency.stripTrailingZeros().toPlainString());
            }
            require(observedNumber==totalNumber && observedCount==totalCount && observedHom==totalHom,
                    "PM3_PUBLISHED_JOINT_ANCESTRY_SUM_MISMATCH");
            if(exact)evidence.put("maximumObservedAncestry",maximumGroup).put("maximumObservedAf",maximum.stripTrailingZeros().toPlainString())
                    .put("AC",totalCount).put("homozygotes",totalHom);
            out.set("populationEvidence",evidence);
            if(rule.faf()) {
                require(exact,"PM3_PUBLISHED_JOINT_FAF_EXACT_ALLELE_REQUIRED");
                BigDecimal faf = popmax(info, ancestry, evidence);
                boolean rare = faf.compareTo(rule.limit()) <= 0;
                return out.put("qualification",rare?"VERIFIED":"FAILED").put("reasonCode",rare?
                        "PM3_PUBLISHED_JOINT_POPMAX_FAF_WITHIN_LIMIT":"PM3_PUBLISHED_JOINT_POPMAX_FAF_EXCEEDS_LIMIT");
            }
            boolean qualifies=!exact || within && totalHom==0;
            out.put("qualification",qualifies?"VERIFIED":"FAILED");
            out.put("reasonCode",!exact?"PM3_PUBLISHED_JOINT_ABSENT_WITH_CALLABILITY"
                    :qualifies?"PM3_PUBLISHED_JOINT_OBSERVED_RARITY":"PM3_PUBLISHED_JOINT_FREQUENCY_OR_HOMOZYGOTES_EXCEED_RULE");
        } catch(IOException | IllegalArgumentException e) {
            String reason=e.getMessage();
            out.put("reasonCode",reason!=null && reason.matches("PM3_[A-Z0-9_]+")?reason:"PM3_PUBLISHED_JOINT_SOURCE_INVALID");
            if(reason!=null && (reason.endsWith("_CONFLICT") || reason.endsWith("_MISMATCH")))out.put("qualification","CONFLICT");
        }
        return out;
    }

    private record Rule(BigDecimal limit, boolean faf) { }

    private Rule threshold(JsonNode observations, JsonNode row) throws IOException {
        var match = row.at("/policySelection/matches/0");
        var source = match.path("source");
        var rule = match.path("jointRarity");
        require(rule.isObject(),"PM3_PUBLISHED_JOINT_RARITY_CLAUSE_REQUIRED");
        String sha = source.path("rawSha256").asText();
        byte[] raw=decoded(observations.path("specificationOriginals").path(sha),sha,8_000_000);
        JsonNode tree=json.readTree(raw);
        var desc=tree.at(source.path("jsonPointer").asText()).at(rule.path("clausePointer").asText());
        var text=desc.path("text").asText().replace("\\","").replaceAll("\\s+"," ").strip();
        require("Applicable".equals(desc.path("applicability").asText()) && "Supporting".equals(desc.path("strength").asText())
                && Pm3SourceDigest.sha256(json,desc).equals(rule.path("clauseSha256").asText()),
                "PM3_PUBLISHED_JOINT_RARITY_CLAUSE_MISMATCH");
        if(match.path("reviewedCaseRule").isObject()) {
            var captured=FAF_THRESHOLD.matcher(text);
            require(FAF_METHOD.equals(rule.path("method").asText()) && captured.matches()
                    && match.path("reviewedCaseRule").path("releaseSha256").asText().equals(sha)
                    && rule.path("clausePointer").asText().startsWith(match.path("reviewedCaseRule").path("pm2Pointer").asText()+"/entContent/strengthDescriptor/"),
                    "PM3_PUBLISHED_JOINT_RARITY_CLAUSE_MISMATCH");
            BigDecimal limit=new BigDecimal(captured.group(1)).scaleByPowerOfTen(Integer.parseInt(captured.group(2)));
            require(limit.compareTo(frequency(rule.path("maximumAlleleFrequency").asText()))==0,
                    "PM3_PUBLISHED_JOINT_RARITY_CLAUSE_MISMATCH");
            return new Rule(limit,true);
        }
        var captured=THRESHOLD.matcher(text);
        require(captured.find() && captured.group(1).equals(rule.path("maximumAlleleFrequency").asText()),
                "PM3_PUBLISHED_JOINT_RARITY_CLAUSE_MISMATCH");
        return new Rule(new BigDecimal(captured.group(1)),false);
    }

    private static JsonNode slice(JsonNode observations, String chromosome, long position) {
        if(!observations.has("jointPopulationOriginals"))return observations.path("jointPopulationOriginal");
        var slices = observations.path("jointPopulationOriginals");
        require(slices.isArray() && slices.size()<=16,"PM3_PUBLISHED_JOINT_INDEXED_SOURCE_INVALID");
        var matching = new ArrayList<JsonNode>();
        for(var s:slices) {
            if(!chromosome.equals(s.at("/source/chromosome").asText()))continue;
            for(var queried:s.at("/source/queriedPositions"))if(queried.isIntegralNumber() && queried.asLong()==position) {
                matching.add(s);
                break;
            }
        }
        require(!matching.isEmpty(),"PM3_PUBLISHED_JOINT_EXACT_SITE_REQUIRED");
        require(matching.size()==1,"PM3_PUBLISHED_JOINT_EXACT_SITE_CONFLICT");
        return matching.get(0);
    }

    private static BigDecimal popmax(Map<String,String> info,ObjectNode ancestry,ObjectNode evidence) {
        BigDecimal reported = frequency(info.get("fafmax_faf95_max_joint"));
        String group = info.getOrDefault("fafmax_faf95_max_gen_anc_joint","");
        require(FAF_GROUPS.contains(group),"PM3_PUBLISHED_JOINT_FAF_POPULATION_REQUIRED");
        BigDecimal maximum = BigDecimal.ZERO;
        for(String population:FAF_GROUPS) {
            BigDecimal faf = frequency(info.get("faf95_joint_"+population));
            ancestry.withObject("/"+population).put("FAF95",faf.toPlainString());
            maximum=maximum.max(faf);
            if(population.equals(group))require(faf.compareTo(reported)==0,"PM3_PUBLISHED_JOINT_FAF_POPMAX_CONFLICT");
        }
        require(maximum.compareTo(reported)==0,"PM3_PUBLISHED_JOINT_FAF_POPMAX_CONFLICT");
        evidence.put("popmaxFaf95",reported.toPlainString()).put("popmaxFaf95Ancestry",group);
        return reported;
    }

    private static BigDecimal frequency(@Nullable String raw) {
        if(raw==null || !raw.matches("[0-9](?:\\.[0-9]{1,20})?(?:[eE][+-]?[0-9]{1,2})?"))
            throw new IllegalArgumentException("PM3_PUBLISHED_JOINT_FAF95_REQUIRED");
        var value=new BigDecimal(raw).stripTrailingZeros();
        require(value.signum()>=0 && value.compareTo(BigDecimal.ONE)<=0 && value.scale()<=40,
                "PM3_PUBLISHED_JOINT_FAF95_INVALID");
        return value;
    }

    private static long count(Map<String,String> info,String field) {
        String value=info.get(field);
        if(value==null || !value.matches("[0-9]{1,9}"))throw new IllegalArgumentException("PM3_PUBLISHED_JOINT_ANCESTRY_COUNT_REQUIRED");
        return Long.parseLong(value);
    }
    private static byte[] decoded(JsonNode data,String sha,int limit) {
        require(data.isTextual() && sha.matches("[a-f0-9]{64}") && data.asText().length() <= 4L*(limit+2)/3,
                "PM3_PUBLISHED_JOINT_ORIGINAL_REQUIRED");
        byte[] raw=Base64.getDecoder().decode(data.asText());
        require(raw.length<=limit && FamilyDeNovoJson.sha256(raw).equals(sha),"PM3_PUBLISHED_JOINT_ORIGINAL_PIN_MISMATCH");
        return raw;
    }
    private static void require(boolean condition,String code) {
        if(!condition)throw new IllegalArgumentException(code);
    }
}
