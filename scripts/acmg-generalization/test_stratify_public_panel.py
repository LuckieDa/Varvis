"""Only invented alleles, sources, models and masked anchors; no real panel reads."""
import copy
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch


ROOT = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location("stratifier", ROOT / "stratify_public_panel.py")
S = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(S)
N = S.N
T = S.module("synthetic_normalization_fixtures", "test_normalize_public_panel.py")


def output_rows(root, logical_name):
    directory = root / "output"
    manifest = json.loads((directory / "manifest.json").read_text())
    path = S.resolve_jsonl_artifact(directory, manifest, logical_name)
    return [json.loads(line) for line in gzip.decompress(path.read_bytes()).decode("utf-8").splitlines()]


def context(index=1):
    return dict(geneSymbol="GENE"+str(index), hgncId="HGNC:"+str(index), mondoId="MONDO:"+str(index),
                moi="AUTOSOMAL_DOMINANT", mechanismSamplingFamily="FAMILY"+str(index % 7), exposureRole="SYNTHETIC", publicAlleleQuota=10)


def model(index=1, strand=1):
    cds = "ATG" + "CGA" * 119 + "TAA"
    coordinates = [(100,189,1,90),(300,389,91,180),(500,682,181,363)] if strand == 1 else [(500,589,1,90),(300,389,91,180),(100,282,181,363)]
    return dict(schemaVersion="native-coding-transcript-model-v1", modelId="SYNTHETIC", modelVersion="1", assembly="GRCh38",
        geneSymbol="GENE"+str(index), hgncId="HGNC:"+str(index), contig=str(index), strand=strand,
        transcript=f"ENST{1000+index}.1", protein=f"ENSP{1000+index}.1", cdsSequence=cds, cdsLength=len(cds), proteinLength=120,
        exons=[dict(ordinal=i,genomicStart=a,genomicEnd=b,cdsStart=c,cdsEnd=d) for i,(a,b,c,d) in enumerate(coordinates,1)],
        transcriptBridge=dict(status="FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE",ensemblTranscript=f"ENST{1000+index}.1",ensemblProtein=f"ENSP{1000+index}.1",
                              refseqTranscript=f"NM_{1000+index}.1",refseqProtein=f"NP_{1000+index}.1"))


def allele(pos, ref, alt, contig="1"):
    return dict(assembly="GRCh38",contig=contig,position=pos,reference=ref,alternate=alt)


def row(value, index=1, gene="GENE1", info=None, **fields):
    raw = S.sha(N.canonical([value,index]))
    info = info if info is not None else gene+":1"
    pairs = [dict(symbol=p.split(":")[0],ncbiGeneId=p.split(":")[1]) for p in info.split("|")]
    projection = dict(CHROM=value["contig"], POS=value["position"], REF=value["reference"], ALT=value["alternate"], ID=str(index), FILTER=".",
                      GENEINFO=info,sourceRawLineSha256=raw,sourceSnapshotIdentity="a"*64,**fields)
    member = dict(sourceGeneInfoPairs=pairs,geneMembershipStatus="EXACT_SINGLE_GENE_MATCH" if len(pairs)==1 else "MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED",
                  alleleEnvelopeStatus="ELIGIBLE_IDENTITY_ONLY",membershipStatus="ELIGIBLE_IDENTITY_ONLY" if len(pairs)==1 else "MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED",
                  samplingEligibility="PENDING_REFERENCE_NORMALIZATION_AND_INDEPENDENT_STRATUM" if len(pairs)==1 else "EXCLUDED_OR_SOURCE_REVIEW_REQUIRED",
                  sourceRawLineSha256=raw,queryGeneSymbol=gene,queryRegion=value["contig"]+":100-1000",queryReturnOrdinal=index,occurrenceLineNumber=index)
    key=N.canonical_key(value["contig"],value["position"],value["reference"],value["alternate"])
    return dict(recordId="R"+str(index),sourceAlleleKey=key,original=value,canonicalKey=key,canonicalAllele=value,
                source=dict(maskedProjection=projection,projectionLineNumber=index,sourceAlleleKey=key), memberships=[member],
                geneMembershipStatuses=[member["geneMembershipStatus"]], normalizationStatus="CANONICAL",reasonCode=None,
                normalizationRecordLocator=dict(file="source-normalization.jsonl",fileSha256="b"*64,lineNumber=index))


def candidate(key, stratum="MISSENSE", gene="GENE1", anchors=(), af="0.1", malformed=False):
    return dict(candidateId=gene+key,geneSymbol=gene,canonicalKey=key,rankSha256=S.rank(gene,stratum,key),matchingAnchorIds=list(anchors),
                mapping=dict(status="ASSIGNED",stratumId=stratum),historicalAf=dict(maximumObserved=af,hasMalformedObservation=malformed))


class MechanicalTests(unittest.TestCase):
    def setUp(self):
        self.plus=S.Model(model(),context())
        self.minus=S.Model(model(strand=-1),context())

    def test_positive_native_codon_three_effects(self):
        cases=[(allele(103,"C","T"),"CODING_STOP_INTERNAL","CGA","TGA"),
               (allele(107,"G","T"),"MISSENSE","CGA","CTA"),
               (allele(108,"A","G"),"SYNONYMOUS","CGA","CGG")]
        for a, expected, before, after in cases:
            result=S.mechanical(a,self.plus)
            self.assertEqual(expected,result["stratumId"])
            self.assertEqual((before,after),(result["eventProof"]["referenceCodon"],result["eventProof"]["alternateCodon"]))

    def test_negative_native_codon_not_genomic_translation(self):
        result=S.mechanical(allele(582,"C","A"),self.minus)  # CDS8 G>T; reference strand is C.
        self.assertEqual("MISSENSE",result["stratumId"])
        self.assertEqual("CTA",result["eventProof"]["alternateCodon"])
        self.assertEqual([8,8],result["eventProof"]["cdsInterval"])

    def test_insertion_reverse_complement_and_biological_index(self):
        result=S.mechanical(allele(578,"T","TC"),self.minus) # between CDS11 and CDS12, inserted genomic C -> coding G
        self.assertEqual("CODING_FRAMESHIFT_INTERNAL",result["stratumId"])
        self.assertEqual(11,result["eventProof"]["cdsEditIndex0"])
        self.assertEqual("G",result["eventProof"]["orientedInserted"])
        self.assertEqual([11,12],result["eventProof"]["cdsInterval"])

    def test_negative_deletion_payload_identity_and_index(self):
        result=S.mechanical(allele(577,"GTC","G"),self.minus)
        self.assertEqual("CODING_FRAMESHIFT_INTERNAL",result["stratumId"])
        self.assertEqual("GA",result["eventProof"]["orientedDeleted"])
        self.assertEqual([11,12],result["eventProof"]["cdsInterval"])
        self.assertEqual(10,result["eventProof"]["cdsEditIndex0"])

    def test_unchanged_anchor_outside_cds_does_not_disqualify_deletion(self):
        value=model()
        value["exons"][0].update(genomicStart=99,cdsStart=0)
        result=S.mechanical(allele(99,"CAT","C"),S.Model(value,context()))
        self.assertEqual("CODING_FRAMESHIFT_INTERNAL",result["stratumId"])
        self.assertEqual([1,2],result["eventProof"]["cdsInterval"])
        self.assertTrue(result["eventProof"]["startCodonOverlap"])

    def test_start_and_native_stop_snvs_excluded_but_frameshifts_preserved(self):
        self.assertEqual("INITIATION_CODON_SNV_EXCLUDED",S.mechanical(allele(100,"A","C"),self.plus)["reasonCode"])
        self.assertEqual("NATIVE_STOP_CODON_SNV_EXCLUDED",S.mechanical(allele(680,"T","C"),self.plus)["reasonCode"])
        result=S.mechanical(allele(680,"T","TG"),self.plus)
        self.assertEqual("TRUNCATING_TERMINAL_CHALLENGE",result["stratumId"])
        self.assertTrue(result["eventProof"]["nativeStopOverlap"])
        self.assertIn("PENDING",result["eventProof"]["ptcAndNmdDerivation"])
        self.assertNotIn("nmdPredicted",result)

    def test_penultimate_last55_uses_changed_interval_intersection(self):
        self.assertEqual("CODING_STOP_INTERNAL",S.mechanical(allele(333,"C","T"),self.plus)["stratumId"]) # CDS124
        self.assertEqual("TRUNCATING_TERMINAL_CHALLENGE",S.mechanical(allele(336,"C","T"),self.plus)["stratumId"]) # CDS127
        self.assertEqual("TRUNCATING_TERMINAL_CHALLENGE",S.mechanical(allele(333,"CGA","C"),self.plus)["stratumId"]) # deleted125..126
        self.assertEqual("CODING_STOP_INTERNAL",S.mechanical(allele(356,"G","A"),self.minus)["stratumId"])
        self.assertEqual("TRUNCATING_TERMINAL_CHALLENGE",S.mechanical(allele(353,"G","A"),self.minus)["stratumId"])

    def test_final_coding_exon_not_final_physical_exon(self):
        value=model()
        value["exons"].append(dict(ordinal=4,genomicStart=800,genomicEnd=809,cdsStart=364,cdsEnd=373))
        self.assertEqual("TRUNCATING_TERMINAL_CHALLENGE",S.mechanical(allele(503,"C","T"),S.Model(value,context()))["stratumId"])

    def test_physical_utr_intron_and_only_first_last_two_excluded(self):
        value=model()
        value["exons"].insert(0,dict(ordinal=1,genomicStart=50,genomicEnd=59,cdsStart=-9,cdsEnd=0))
        for i,e in enumerate(value["exons"],1):e["ordinal"]=i
        bound=S.Model(value,context())
        self.assertEqual("CANONICAL_INTRON_BOUNDARY_EVENT",S.mechanical(allele(61,"A","G"),bound)["reasonCode"])
        result=S.mechanical(allele(62,"A","G"),bound)
        self.assertEqual("NONCANONICAL_INTRONIC",result["stratumId"])
        self.assertEqual(1,result["eventProof"]["intronAfterPhysicalExonOrdinal"])
        self.assertEqual("NONCANONICAL_INTRONIC",S.mechanical(allele(97,"A","G"),bound)["stratumId"])
        self.assertEqual("CANONICAL_INTRON_BOUNDARY_EVENT",S.mechanical(allele(98,"A","G"),bound)["reasonCode"])

    def test_coding_boundaries_full_payload_and_both_insertion_flanks(self):
        self.assertEqual("CROSS_EXON_OR_UNRESOLVED_TRANSCRIPT_EVENT",S.mechanical(allele(189,"A","AT"),self.plus)["reasonCode"])
        self.assertEqual("UNRESOLVED",S.mechanical(allele(107,"A","T"),self.plus)["status"])
        self.assertEqual("EXCLUDED",S.mechanical(allele(107,"GC","TT"),self.plus)["status"])
        self.assertEqual("CODING_INFRAME_INDEL_NOT_IN_FIXED_STRATA",S.mechanical(allele(107,"G","GAAA"),self.plus)["reasonCode"])

    def test_complete_model_integer_and_continuity_guards(self):
        for field,value in [("cdsLength",363.0),("proteinLength",120.0)]:
            bad=model();bad[field]=value
            with self.assertRaisesRegex(ValueError,"MODEL_CDS_INVALID"):S.Model(bad,context())
        bad=model();bad["exons"][1]["cdsStart"]=92
        with self.assertRaisesRegex(ValueError,"MODEL_EXON_CONTINUITY_INVALID"):S.Model(bad,context())


class SamplingTests(unittest.TestCase):
    def test_exact_historical_af_missing_zero_and_discordance(self):
        a=row(allele(108,"A","G"),AF_ESP="0",AF_EXAC=".",AF_TGP="")
        b=row(allele(108,"A","G"),2,AF_ESP="1e-2")
        result=S.historical_af([a,b])
        self.assertEqual("0.01",result["maximumObserved"])
        self.assertFalse(result["hasMalformedObservation"])
        self.assertEqual({"DOT","EMPTY","ABSENT"},{c["reasonCode"] for c in result["cells"] if c["status"]=="UNAVAILABLE"})
        self.assertEqual("SOURCE_AF_DISCORDANCE",result["discordance"][0]["status"])
        self.assertEqual("0",result["discordance"][0]["minimum"])

    def test_malformed_is_not_unavailable(self):
        for raw in ["1,2","NaN","Infinity","1.0001","-0.1","0.1%"," 0.1",None]:
            self.assertTrue(S.historical_af([row(allele(108,"A","G"),AF_ESP=raw)])["hasMalformedObservation"],repr(raw))

    def test_gene_hash_exact_bytes_no_trailing_lf(self):
        raw=b"VARVIS-TEN-RULE-GENERALIZATION-PUBLIC-V1-20260913\nGENE1\nMISSENSE\nGRCh38|1|107|G|T"
        self.assertEqual(hashlib.sha256(raw).hexdigest(),S.rank("GENE1","MISSENSE","GRCh38|1|107|G|T"))
        self.assertNotEqual(hashlib.sha256(raw+b"\n").hexdigest(),S.rank("GENE1","MISSENSE","GRCh38|1|107|G|T"))

    def test_more_than_two_anchors_never_replace_two_independent(self):
        rows=[candidate(str(i),anchors=["A"+str(i)] if i<4 else []) for i in range(7)]
        selected,_,_=S.allocate([context()],rows,{str(i) for i in range(4)})
        self.assertEqual(4,len(selected))
        self.assertEqual(2,sum(bool(r["matchingAnchorIds"]) for r in selected))
        self.assertEqual(2,sum(r["canonicalKey"] not in {str(i) for i in range(4)} for r in selected))

    def test_all_anchor_universe_applies_even_other_gene_role(self):
        rows=[candidate(str(i)) for i in range(5)]
        selected,_,_=S.allocate([context()],rows,{"0","1"})
        self.assertEqual(3,len(selected))
        self.assertTrue(all(r["canonicalKey"] not in {"0","1"} for r in selected))

    def test_zero_or_one_anchor_fills_more_independent(self):
        for count in [0,1]:
            rows=[candidate(str(i),anchors=["A"] if i<count else []) for i in range(6)]
            selected,_,_=S.allocate([context()],rows,{"0"} if count else set())
            self.assertEqual(4,len(selected))
            self.assertEqual(count,sum(bool(r["matchingAnchorIds"]) for r in selected))

    def test_global_dedup_frame_order_and_no_quota_transfer(self):
        rows=[candidate("same","CODING_STOP_INTERNAL",gene="GENE1"),candidate("same","CODING_STOP_INTERNAL",gene="GENE2"),
              candidate("next","CODING_STOP_INTERNAL",gene="GENE2")]
        selected,gaps,_=S.allocate([context(),context(2)],rows,set())
        self.assertEqual([("GENE1","same"),("GENE2","next")],[(r["geneSymbol"],r["canonicalKey"]) for r in selected])
        self.assertEqual(18,len(gaps))

    def test_malformed_or_allmissing_af_leaves_only_highest_slot_gap(self):
        for malformed in [False,True]:
            rows=[candidate("a","SYNONYMOUS",af="0.9" if malformed else None,malformed=malformed),candidate("b","SYNONYMOUS",af=None)]
            selected,gaps,_=S.allocate([context()],rows,set())
            self.assertEqual([2],[r["slot"] for r in selected])
            self.assertIn("MALFORMED_SYNONYMOUS_AF_POOL_REQUIRES_REVIEW" if malformed else "NO_OBSERVED_HISTORICAL_AF",[g["reasonCode"] for g in gaps])

    def test_highest_exact_decimal_then_independent_remaining_hash(self):
        rows=[candidate("a","SYNONYMOUS",af="0.10000000000000000000001"),candidate("b","SYNONYMOUS",af="0.1"),candidate("c","SYNONYMOUS",af=None)]
        selected,_,_=S.allocate([context()],rows,set())
        self.assertEqual("a",selected[0]["canonicalKey"])
        self.assertEqual(min(rows[1:],key=lambda x:x["rankSha256"])["canonicalKey"],selected[1]["canonicalKey"])

    def test_multigene_original_flag_retained_with_separate_model_mapping(self):
        a=row(allele(107,"G","T"),info="GENE1:1|GENE2:2")
        m2=model(2);m2["contig"]="1"
        result=S.assemble([context(),context(2)],{"GENE1":S.Model(model(),context()),"GENE2":S.Model(m2,context(2))},[a],{a["canonicalKey"]:[a]},[])
        self.assertEqual(2,len(result["assignments"]))
        self.assertTrue(all(r["originalGeneMembershipStatuses"]==["MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED"] for r in result["assignments"]))
        self.assertEqual(1,len(result["selected"]))

    def test_conflicting_geneid_alias_is_unresolved(self):
        a=row(allele(107,"G","T"));b=row(allele(107,"G","T"),2,info="GENE1:99")
        result=S.assemble([context()],{"GENE1":S.Model(model(),context())},[a,b],{a["canonicalKey"]:[a,b]},[])
        self.assertFalse(result["assignments"])
        self.assertEqual("UNRESOLVED",result["sourceIssues"][0]["status"])


class UnknownSourceFrameTests(unittest.TestCase):
    def setUp(self):
        self.contexts = [context(i) for i in range(1, 21)]
        self.models = {c["geneSymbol"]: S.Model(model(i), c) for i, c in enumerate(self.contexts, 1)}

    def missing_source(self, value):
        source = row(value)
        source.update(referenceStatus="MATCH", localHaplotypeEqualityVerified=True)
        source["source"]["maskedProjection"].pop("GENEINFO")
        source["geneMembershipStatuses"] = ["GENEINFO_MISSING_OR_MALFORMED"]
        source["memberships"][0].update(sourceGeneInfoPairs=[], geneMembershipStatus="GENEINFO_MISSING_OR_MALFORMED")
        return source

    def assemble(self, sources):
        return S.assemble(self.contexts, self.models, sources, {sources[0]["canonicalKey"]: sources}, [])

    def test_disjoint_missing_gene_keeps_source_unknown_and_full_accounting(self):
        source = self.missing_source(allele(683, "C", "T"))
        original = copy.deepcopy(source)
        result = self.assemble([source])
        issue = result["sourceIssues"][0]
        self.assertEqual("EXCLUDED", issue["status"])
        self.assertEqual("OUTSIDE_COMPLETE_MECHANICAL_MODEL_SPANS", issue["reasonCode"])
        self.assertEqual("UNRESOLVED", issue["sourceGeneIdentityStatus"])
        self.assertEqual("SOURCE_GENE_IDENTITY_UNRESOLVED", issue["sourceGeneIdentityReasonCode"])
        self.assertEqual([source["recordId"]], issue["sourceRecordIds"])
        proof = issue["disjointnessProof"]
        self.assertEqual(20, len(proof["modelSpans"]))
        self.assertFalse(any(span["intersects"] for span in proof["modelSpans"]))
        self.assertFalse(proof["sourceGeneIdentityResolved"])
        self.assertIsNone(proof["geneAssignment"])
        self.assertEqual(0, proof["publicPanelQuotaCredit"])
        self.assertEqual([source["normalizationRecordLocator"]], proof["normalizationRecordLocators"])
        self.assertEqual(original, source)
        self.assertEqual(original["source"], result["sourceAccounting"][0]["source"])
        self.assertEqual(original["memberships"], result["sourceAccounting"][0]["memberships"])
        self.assertFalse(result["assignments"] or result["selected"])

    def test_closed_endpoints_and_intronic_positions_stay_unresolved(self):
        for position in (100, 189, 250, 300, 500, 682):
            with self.subTest(position=position):
                issue = self.assemble([self.missing_source(allele(position, "C", "T"))])["sourceIssues"][0]
                self.assertEqual("UNRESOLVED", issue["status"])
                self.assertNotIn("disjointnessProof", issue)
        self.assertEqual("EXCLUDED", self.assemble([self.missing_source(allele(99, "C", "T"))])["sourceIssues"][0]["status"])

    def test_all_models_on_same_chromosome_must_be_checked(self):
        other = model(2)
        other["contig"] = "1"
        for exon in other["exons"]:
            exon["genomicStart"] += 1
            exon["genomicEnd"] += 1
        self.models["GENE2"] = S.Model(other, context(2))
        self.assertEqual("UNRESOLVED", self.assemble([self.missing_source(allele(683, "C", "T"))])["sourceIssues"][0]["status"])

    def test_physical_utr_and_negative_strand_are_not_trimmed(self):
        for strand in (1, -1):
            physical = model(strand=strand)
            if strand == 1:
                physical["exons"].insert(0, dict(ordinal=1, genomicStart=50, genomicEnd=59, cdsStart=-9, cdsEnd=0))
                position = 55
            else:
                physical["exons"].insert(0, dict(ordinal=1, genomicStart=700, genomicEnd=709, cdsStart=-9, cdsEnd=0))
                position = 705
            for ordinal, exon in enumerate(physical["exons"], 1):
                exon["ordinal"] = ordinal
            self.models["GENE1"] = S.Model(physical, context())
            self.assertEqual("UNRESOLVED", self.assemble([self.missing_source(allele(position, "C", "T"))])["sourceIssues"][0]["status"])

    def test_non_snv_incomplete_models_or_noncanonical_alias_cannot_prove_exclusion(self):
        self.assertEqual("UNRESOLVED", self.assemble([self.missing_source(allele(800, "C", "CT"))])["sourceIssues"][0]["status"])
        source = self.missing_source(allele(800, "C", "T"))
        source["normalizationStatus"] = "UNRESOLVED"
        self.assertIsNone(S.unresolved_source_disjoint_proof([source], self.contexts, self.models))
        source["normalizationStatus"] = "CANONICAL"
        source["referenceStatus"] = "MISSING"
        self.assertIsNone(S.unresolved_source_disjoint_proof([source], self.contexts, self.models))
        source["referenceStatus"] = "MATCH"
        source["localHaplotypeEqualityVerified"] = False
        self.assertIsNone(S.unresolved_source_disjoint_proof([source], self.contexts, self.models))
        source["localHaplotypeEqualityVerified"] = True
        self.models.pop("GENE20")
        self.assertEqual("UNRESOLVED", self.assemble([source])["sourceIssues"][0]["status"])

    def test_disjoint_conflicting_aliases_keep_both_original_identity_records(self):
        first = row(allele(800, "C", "T"))
        second = row(allele(800, "C", "T"), 2, info="GENE1:99")
        for source in (first, second):
            source.update(referenceStatus="MATCH", localHaplotypeEqualityVerified=True)
        result = self.assemble([first, second])
        issue = result["sourceIssues"][0]
        self.assertEqual(("EXCLUDED", "UNRESOLVED"), (issue["status"], issue["sourceGeneIdentityStatus"]))
        self.assertEqual([first["recordId"], second["recordId"]], issue["sourceRecordIds"])
        self.assertEqual(2, len(result["sourceAccounting"]))
        self.assertEqual([first["source"], second["source"]], [item["source"] for item in result["sourceAccounting"]])


class PipelineFixture:
    def __init__(self,root):
        self.root=root
        f=T.Fixture(root,"A"*1100,start=100,end=1000)
        self.plan,self.query,self.pool,_=T.source_inputs(f)
        self.plan["version"]="1.0.1"
        self.contract=json.loads((ROOT/"fixtures"/"stratification-contract-synthetic-v1.json").read_text(encoding="utf-8"))
        self.frame=dict(schemaVersion="prospective-public-allele-sampling-frame-v1",requirements=dict(fixedGenes=20,fixedPerGeneQuota=10,plannedUniqueAlleles=200),
            sampling=dict(deterministicSeed=S.SEED,strataPerGene=[dict(id=k,quota=v) for k,v in S.STRATA]),contexts=[context(i) for i in range(1,21)])
        binding=self.contract["bindings"]
        binding["frameSha256"]=T.dump(root/"frame.json",self.frame)
        prior=dict(schemaVersion="masked-source-prerank-freeze-v1",preservedProspectiveInputs=[dict(file="synthetic-prior.json",sha256="f"*64)])
        binding["privatePrerankManifestSha256"]=T.dump(root/"prerank.json",prior)
        self.query["frameBinding"]["fileSha256"]=binding["frameSha256"]
        self.query["totals"].update(queryWindows=20,queriedBasesIncludingPadding=18020)
        self.query["sources"][0]["sourceId"]="SYNTHETIC"
        self.query["geneQueries"]=[]
        self.source_rows=[]
        values=[(103,"C","T"),(119,"G","GT"),(503,"C","T"),(107,"G","T"),(110,"G","T"),(113,"G","T"),(116,"G","T"),(108,"A","G"),(111,"A","G"),(202,"A","C")]
        for i in range(1,21):
            self.query["geneQueries"].append(dict(geneSymbol="GENE"+str(i),sourceGeneInterval=dict(assembly="GRCh38",chromosome=str(i)),
                queryWindows=[dict(start=100,end=1000,tabixRegion=str(i)+":100-1000")],queryPartitions=[dict(partitionId="ONE",firstWindowOrdinal=1,lastWindowOrdinal=1,maximumRowObservations=150000,maximumRawUtf8Bytes=1048576)]))
            for ordinal,(pos,ref,alt) in enumerate(values,1):
                r=row(allele(pos,ref,alt,str(i)),len(self.source_rows)+1,gene="GENE"+str(i),info=f"GENE{i}:{i}",AF_ESP="0.1",MC="SYNTHETIC_IGNORED_LABEL")
                r["memberships"][0]["queryReturnOrdinal"]=ordinal
                self.source_rows.append(r)
        self.models=dict(schemaVersion="native-twenty-model-execution-v1",status="COMPLETE",sourceKind="VEP116_OFFLINE_CACHE_TRANSCRIPT_OBJECT_NOT_REST",
            independentRnaSource="NCBI_VERSIONED_REFSEQ_GENBANK",defaultEnabled=False,clinicalAcceptance=False,entries=[])
        self.review=dict(schemaVersion="independent-native-twenty-model-source-review-v1",status="TWENTY_MODEL_AND_349_ARCHIVED_RETURNED_EXON_HASHES_MATCH_INDEPENDENT_REFSEQ_RNA",bindings={},records=[])
        self.gene_bridge=dict(schemaVersion="reviewed-geneinfo-identity-bridge-v1",bridgeId="SYNTHETIC",version="1.0.0",
            review=dict(status="REVIEWED_SOURCE_IDENTITY_BRIDGE",reviewId="SYNTHETIC",reviewedAt="2026-01-01",reviewer="SYNTHETIC",clinicalSignoff=False),
            bindings=dict(frameSha256=binding["frameSha256"],rnaSourceReviewSha256="d"*64),scope="SYNTHETIC",records=[],boundaries=["SYNTHETIC"])
        for i in range(1,21):
            m=model(i); name=f"GENE{i}.json"; digest=T.dump(root/name,m); canonical=S.sha(S.canonical(m))
            self.models["entries"].append(dict(geneSymbol=f"GENE{i}",transcript=m["transcript"],refseqTranscript=m["transcriptBridge"]["refseqTranscript"],
                status="FULL_RNA_EXON_CDS_TRANSLATION_AGREEMENT",file=name,fileSha256=digest,canonicalSha256=canonical,cdsLength=363,exonCount=3))
            self.review["records"].append(dict(geneSymbol=f"GENE{i}",transcript=m["transcript"],refSeqTranscript=m["transcriptBridge"]["refseqTranscript"],
                modelFile=name,modelFileSha256=digest,modelCanonicalSha256=canonical,cdsSha256=S.sha(m["cdsSequence"].encode()),status="EXACT_SOURCE_AND_ARCHIVED_NATIVE_READ_RECEIPTS_MATCH"))
            self.review["records"][-1]["sourceFileSha256"]="c"*64
            self.gene_bridge["records"].append(dict(geneSymbol=f"GENE{i}",hgncId=f"HGNC:{i}",ncbiGeneId=str(i),clinvarGeneinfoToken=f"GENE{i}:{i}",
                source=dict(relativePath=f"source-{i}.gb",url="https://example.invalid/SYNTHETIC",sha256="c"*64,transcript=f"NM_{1000+i}.1",locator="SYNTHETIC",
                            rawGeneQualifier=f'/gene="GENE{i}"',rawGeneIdQualifier=f'/db_xref="GeneID:{i}"',rawHgncQualifier=f'/db_xref="HGNC:HGNC:{i}"')))
        binding["nativeModelsManifestFileSha256"]=T.dump(root/"models.json",self.models)
        self.review["bindings"]["modelManifestFileSha256"]=binding["nativeModelsManifestFileSha256"]
        binding["nativeModelSourceReviewSha256"]=T.dump(root/"model-review.json",self.review)
        binding["populationHeaderSha256"]="e"*64
        header=dict(sourceId="SYNTHETIC",headerSha256="e"*64,sourceRowsRead=0,networkCalls=0,
                    fieldDefinitions=[f'##INFO=<ID={key},Number=1,Type=Float,Description="SYNTHETIC">' for key in S.AF_FIELDS])
        binding["populationHeaderReceiptSha256"]=T.dump(root/"header.json",header)
        self.anchors=dict(schemaVersion="masked-frozen-anchor-identities-v1",bindings=dict(frameSha256=binding["frameSha256"],prerankManifestSha256=binding["privatePrerankManifestSha256"]),
            sourceFiles=prior["preservedProspectiveInputs"],records=[],unresolvedAnchors=[],sourceCoverage=[dict(file="synthetic-prior.json",sha256="f"*64,exactDnaRoleRecordCount=0,unresolvedProteinAnchorCount=0,review="SYNTHETIC")],
            review=dict(status="REVIEWED_SOURCE_IDENTITY_PROJECTION",reviewedAt="2026-01-01",reviewer="SYNTHETIC",allFrozenAnchorsAccountedFor=True,exactDnaIdentityComplete=True))
        self.options=types.SimpleNamespace(contract=root/"contract.json",contract_sha256=None,frame=root/"frame.json",query_plan=root/"query.json",
            normalization_plan=root/"plan.json",pool_manifest=root/"pool.json",projection=root/"sampling-projection.jsonl",occurrences=root/"query-occurrences.jsonl",
            normalization_manifest=root/"normalization"/"manifest.json",normalization_manifest_sha256=None,models_manifest=root/"models.json",model_source_review=root/"model-review.json",
            population_header_receipt=root/"header.json",prerank_manifest=root/"prerank.json",anchor_projection=root/"anchors.json",anchor_projection_sha256=None,task_output_root=root,output=root/"output")
        self.options.geneinfo_bridge=root/"gene-bridge.json"
        self.options.geneinfo_bridge_sha256=T.dump(self.options.geneinfo_bridge,self.gene_bridge)
        self.write_sources()

    def write_sources(self):
        root=self.root; binding=self.contract["bindings"]
        binding["queryPlanSha256"]=T.dump(root/"query.json",self.query)
        files={"sampling-projection.jsonl":[r["source"]["maskedProjection"] for r in self.source_rows],
               "query-occurrences.jsonl":[{k:v for k,v in r["memberships"][0].items() if k!="occurrenceLineNumber"} for r in self.source_rows]}
        artifacts=[]
        for name,rows in files.items():
            content=b"".join(S.canonical(r)+b"\n" for r in rows);(root/name).write_bytes(content)
            artifacts.append(dict(file=name,bytes=len(content),sha256=S.sha(content)))
        binding["maskedProjectionSha256"]=artifacts[0]["sha256"];binding["queryOccurrencesSha256"]=artifacts[1]["sha256"]
        self.pool.update(planSha256=binding["queryPlanSha256"],frameBinding=self.query["frameBinding"],plannedTotals=self.query["totals"],sourceSnapshotIdentity=self.query["sources"][0],
            uniqueRawRows=len(self.source_rows),retainedRowObservations=len(self.source_rows),artifacts=artifacts,genes=[])
        for q in self.query["geneQueries"]:
            count=sum(r["memberships"][0]["queryGeneSymbol"]==q["geneSymbol"] for r in self.source_rows)
            self.pool["genes"].append(dict(geneSymbol=q["geneSymbol"],status="COMPLETE",windows=[dict(queryRegion=q["queryWindows"][0]["tabixRegion"],status="COMPLETE",retainedObservations=count)],partitions=[dict(q["queryPartitions"][0],status="COMPLETE")]))
        binding["poolManifestSha256"]=T.dump(root/"pool.json",self.pool)
        self.plan["bindings"]={k:binding[k] for k in ("frameSha256","queryPlanSha256","poolManifestSha256","maskedProjectionSha256","queryOccurrencesSha256")}
        self.plan["bindings"]["provisionalPrerankManifestSha256"]=binding["privatePrerankManifestSha256"]
        self.plan["queryPlanSha256"]=binding["queryPlanSha256"]
        self.plan["windowPolicy"].update(originalQueryWindowCount=20,originalTotalBases=18020)
        binding["normalizationPlanSha256"]=T.dump(root/"plan.json",self.plan)
        windows,lookup=N.validate_plan(self.plan,self.query)
        originals,identity=N.load_candidates(self.plan,self.query,root/"pool.json",root/"sampling-projection.jsonl",root/"query-occurrences.jsonl",windows,lookup)
        results=[dict(status="CANONICAL",referenceStatus="MATCH",canonicalAllele=r["original"],canonicalKey=r["source"]["sourceAlleleKey"],original=r["original"],attempts=[],localHaplotypeEqualityVerified=True,leftmostProof="SYNTHETIC_ALREADY_CANONICAL") for r in originals]
        records,attempts,groups=N.output_records(originals,results)
        folder=root/"normalization";folder.mkdir(exist_ok=True);artifacts=[]
        for name,data in [("source-normalization.jsonl",records),("window-attempts.jsonl",attempts),("canonical-aliases.jsonl",groups)]:
            content=b"".join(S.canonical(r)+b"\n" for r in data);(folder/name).write_bytes(content)
            artifacts.append(dict(file=name,bytes=len(content),sha256=S.sha(content)))
        report=dict(schemaVersion="public-panel-normalization-manifest-v1",toolVersion="1.0.1",status="COMPLETE",allSourceRecordsAccountedFor=True,unresolvedRecords=0,samplingAllowed=False,
            planSha256=binding["normalizationPlanSha256"],queryPlanSha256=binding["queryPlanSha256"],inputIdentities=identity,finalPanelSelectedAlleles=0,ruleEvaluations=0,networkCalls=0,
            artifactIdentities=artifacts,sourceRecords=len(records),canonicalSourceRecords=len(records),excludedSourceRecords=0,uniqueCanonicalAlleles=len(groups),referenceIdentity=dict(boundary="SYNTHETIC"),normalizerIdentity=dict(boundary="SYNTHETIC"))
        self.options.normalization_manifest_sha256=T.dump(folder/"manifest.json",report)
        self.options.anchor_projection_sha256=T.dump(root/"anchors.json",self.anchors)
        self.options.contract_sha256=T.dump(root/"contract.json",self.contract)

    def prepare_v2_inputs(self):
        # Entirely invented source-probe/anchor inputs; never read private real identities.
        root=self.root
        if self.anchors["schemaVersion"] == "masked-frozen-anchor-identities-v1":
            prior=copy.deepcopy(self.anchors)
            current=copy.deepcopy(prior)
            current.update(schemaVersion="masked-frozen-anchor-identities-v2",supplementaryRecords=[],identitySources=[],identityResolutions=[])
        else:
            prior=copy.deepcopy(self.prior_anchors)
            current=copy.deepcopy(self.anchors)
        self.options.prior_anchor_projection=root/"prior-anchors.json"
        prior_sha=T.dump(self.options.prior_anchor_projection,prior)
        current["bindings"]["priorProjectionSha256"]=prior_sha
        self.options.anchor_projection_sha256=T.dump(root/"anchors.json",current)
        resolution=json.loads((ROOT/"fixtures"/"anchor-resolution-contract-synthetic-v2.json").read_text(encoding="utf-8"))
        resolution["bindings"]=dict(originalStratificationContractSha256=self.options.contract_sha256,
            frameSha256=self.contract["bindings"]["frameSha256"],priorProjectionSha256=prior_sha,
            priorCompletionContractSha256=S.PRIOR_COMPLETION_CONTRACT_SHA)
        self.options.anchor_resolution_contract=root/"anchor-contract.json"
        self.options.anchor_resolution_contract_sha256=T.dump(self.options.anchor_resolution_contract,resolution)
        if hasattr(self.options,"source_discovery_projection"):
            return
        prepare=dict(schemaVersion="synthetic-transcript-source-probes-v1",dataClass="SYNTHETIC",formalTopN=False,clinicalAcceptance=False,
            ruleEvaluations=0,referenceQueries=20,referenceBasesRead=20,vcfSha256=None,probes=[])
        records=[];vcf="#fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
        for i in range(1,21):
            identifier=f"SYNTHETIC_PROBE_{i}";a=allele(103,"C","T",str(i))
            read=dict(fastaContig="chr"+str(i),position1=103,fileOffset=1000+i,observedOriginalByteHex="43",bytesRead=1)
            prepare["probes"].append(dict(id=identifier,assembly="GRCh38",publicAlleleClaim=False,
                syntheticAllele=dict(contig=str(i),position=103,ref="C",alt="T"),referenceRead=read))
            vcf+=f"{i}\t103\t{identifier}\tC\tT\t.\t.\t.\n"
            records.append(dict(probeId=identifier,allele=a,canonicalKey=N.canonical_key(str(i),103,"C","T"),
                sourceRole="SYNTHETIC_TRANSCRIPT_SOURCE_DISCOVERY_ONLY",referenceValidationStatus="MATCH_FROM_ARCHIVED_SINGLE_BASE_READ",
                sourceLocators=dict(prepareProbeIndex0=i-1,vcfDataRow1=i,referenceRead=read)))
        self.options.source_discovery_vcf=root/"synthetic-probes.vcf"
        self.options.source_discovery_vcf.write_text(vcf,encoding="ascii")
        prepare["vcfSha256"]=S.sha(self.options.source_discovery_vcf.read_bytes())
        self.options.source_discovery_prepare=root/"synthetic-prepare.json"
        prepare_sha=T.dump(self.options.source_discovery_prepare,prepare)
        projection=dict(schemaVersion="reviewed-source-discovery-exposure-projection-v1",projectionId="SYNTHETIC_ONLY",version="1.0.0",
            review=dict(status="REVIEWED_COMPLETE_SOURCE_DISCOVERY_IDENTITIES",reviewId="SYNTHETIC",reviewer="SYNTHETIC",reviewedAt="2026-01-01",
                allOriginalProbesAccountedFor=True,originalReferenceReadReceiptsVerified=True,clinicalSignoff=False),
            bindings=dict(probeManifestSha256=prepare_sha,inputVcfSha256=prepare["vcfSha256"],executionStartSha256="1"*64,
                executionFinishSha256="2"*64,transcriptExportSha256="3"*64,vepOutputSha256="4"*64),probeCount=20,records=records,
            boundaries=dict(selectionEffect="NONE",criterionEvaluations=0,clinicalAcceptance=False))
        self.options.source_discovery_projection=root/"probe-projection.json"
        self.options.source_discovery_projection_sha256=T.dump(self.options.source_discovery_projection,projection)


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="synthetic-stratification-")
        self.root=Path(self.temp.name)
        self.fixture=PipelineFixture(self.root)

    def tearDown(self):self.temp.cleanup()

    def run_pipeline(self):
        self.fixture.prepare_v2_inputs()
        return S.execute(self.fixture.options)

    def test_twenty_gene_200_synthetic_structure_only_no_rule_calls(self):
        report=self.run_pipeline()
        self.assertEqual("COMPLETE",report["status"],report)
        self.assertEqual(200,report["globallyDistinctSelectedAlleles"])
        self.assertEqual([10]*20,[r["selected"] for r in report["perGeneSelectedCounts"]])
        self.assertFalse(report["clinicalAcceptance"])
        self.assertTrue(all(report[k]==0 for k in ("networkCalls","fastaReads","vepCalls","predictorCalls","criterionEvaluations","rawClassificationRowsRead")))
        self.assertEqual(report,json.loads((self.root/"output"/"manifest.json").read_text()))
        sources={r["recordId"]:r for r in output_rows(self.root,"source-accounting.jsonl")}
        candidates=output_rows(self.root,"candidate-strata.jsonl")
        self.assertEqual(200,len(candidates))
        for candidate in candidates:
            self.assertTrue(set(candidate["sourceIdentityLocator"]["recordIds"]) <= set(sources))
            self.assertIn(candidate["contextLocator"]["geneSymbol"],{c["geneSymbol"] for c in report["frameContexts"]})

    def test_unknown_contract_operator_rejected_before_output_creation(self):
        self.fixture.contract["requiredInputs"].append(dict(id="ADDITIONAL_HIDDEN_REQUIREMENT"))
        self.fixture.options.contract_sha256=T.dump(self.root/"contract.json",self.fixture.contract)
        with self.assertRaisesRegex(ValueError,"CONTRACT_OPERATOR_UNSUPPORTED"):self.run_pipeline()
        self.assertFalse((self.root/"output").exists())

    def test_native_reference_cds_mismatch_stays_all_case_unresolved(self):
        r=self.fixture.source_rows[3];r["source"]["maskedProjection"]["REF"]="A"
        self.fixture.write_sources()
        report=self.run_pipeline()
        self.assertEqual("INCOMPLETE",report["status"],report)
        self.assertEqual(1,report["unresolvedCandidateContexts"])
        self.assertEqual(200,report["sourceRecords"])
        self.assertFalse(report["selectedRecordsFinal"])

    def test_malformed_af_blocks_highest_slot_without_replacing_it(self):
        self.fixture.source_rows[7]["source"]["maskedProjection"]["AF_ESP"]="oops"
        self.fixture.write_sources();report=self.run_pipeline()
        self.assertEqual("INCOMPLETE",report["status"],report)
        self.assertEqual(199,report["selectedAlleles"])
        self.assertEqual(1,report["missingSlots"])

    def test_original_mc_label_does_not_drive_native_stratum(self):
        for r in self.fixture.source_rows:r["source"]["maskedProjection"]["MC"]="nonsense,benign,highscore,SYNTHETIC_IGNORED"
        self.fixture.write_sources();report=self.run_pipeline()
        self.assertEqual("COMPLETE",report["status"],report)
        self.assertEqual(200,report["selectedAlleles"])

    def test_original_projection_tamper_not_resealed_is_rejected(self):
        with (self.root/"sampling-projection.jsonl").open("ab") as f:f.write(b" ")
        report=self.run_pipeline()
        self.assertEqual("FAILED",report["status"])
        self.assertFalse(report["panelComplete"])

    def test_normalization_alias_tamper_not_resealed_is_rejected(self):
        with (self.root/"normalization"/"canonical-aliases.jsonl").open("ab") as f:f.write(b" ")
        report=self.run_pipeline()
        self.assertEqual("FAILED",report["status"])
        self.assertFalse((self.root/"output"/"selected-panel.jsonl.gz").exists())

    def test_model_review_hash_chain_does_not_accept_relabelled_model(self):
        value=json.loads((self.root/"GENE1.json").read_text());value["transcript"]="ENST999.1"
        T.dump(self.root/"GENE1.json",value)
        report=self.run_pipeline()
        self.assertEqual("FAILED",report["status"])
        self.assertFalse(report["panelComplete"])

    def test_header_type_change_resealed_still_rejected(self):
        value=json.loads((self.root/"header.json").read_text());value["fieldDefinitions"][0]=value["fieldDefinitions"][0].replace("Number=1","Number=A")
        self.fixture.contract["bindings"]["populationHeaderReceiptSha256"]=T.dump(self.root/"header.json",value)
        self.fixture.options.contract_sha256=T.dump(self.root/"contract.json",self.fixture.contract)
        report=self.run_pipeline()
        self.assertEqual("FAILED",report["status"])
        self.assertEqual("HISTORICAL_AF_HEADER_TYPE_UNSUPPORTED",report["reasonCode"])

    def test_unresolved_protein_only_anchor_is_retained_without_fabricated_dna(self):
        anchor=dict(anchorId="SYNTHETIC_UNRESOLVED",geneSymbol="OUTSIDE",hgncId=None,mondoId=None,moi="AD",sourceFile="synthetic-prior.json",sourceFileSha256="f"*64,
            sourceRecordLocator="line 1",originalFixedAt="2026-01-01",exposureRole="SYNTHETIC",criterionExposureRoles={},sourceRole="FROZEN_PUBLIC_ANCHOR",originalProteinIdentity="p.Arg2Trp",reason="GENOMIC_IDENTITY_NOT_FROZEN")
        self.fixture.anchors["unresolvedAnchors"]=[anchor];self.fixture.anchors["review"]["exactDnaIdentityComplete"]=False
        self.fixture.anchors["sourceCoverage"][0]["unresolvedProteinAnchorCount"]=1
        self.fixture.options.anchor_projection_sha256=T.dump(self.root/"anchors.json",self.fixture.anchors)
        report=self.run_pipeline()
        self.assertEqual("INCOMPLETE",report["status"],report)
        self.assertEqual(200,report["selectedAlleles"])
        self.assertEqual(1,report["unreconciledAnchors"])
        result=output_rows(self.root,"preserved-anchors.jsonl")[0]
        self.assertIsNone(result["canonicalKey"])
        self.assertTrue(result["originalAllCaseDenominatorRetained"])

    def test_missing_freeze_source_or_hidden_anchor_requirement_rejected(self):
        self.fixture.anchors["additionalRequirements"]="ignored operator must fail"
        self.fixture.options.anchor_projection_sha256=T.dump(self.root/"anchors.json",self.fixture.anchors)
        report=self.run_pipeline()
        self.assertEqual("FAILED",report["status"])
        self.assertFalse(report["panelComplete"])

    def add_exact_anchor(self,source_row,identifier):
        a=dict(anchorId=identifier,**{k:context()[k] for k in ("geneSymbol","hgncId","mondoId","moi")},
            originalAllele=source_row["original"],originalSourceKey=source_row["sourceAlleleKey"],sourceFile="synthetic-prior.json",sourceFileSha256="f"*64,
            sourceRecordLocator="original row "+identifier,originalFixedAt="2026-01-01",exposureRole="SYNTHETIC_ANCHOR",criterionExposureRoles={"PS3":"SYNTHETIC_DEVELOPER"},sourceRole="FROZEN_PUBLIC_ANCHOR")
        self.fixture.anchors["records"].append(a)
        self.fixture.anchors["sourceCoverage"][0]["exactDnaRoleRecordCount"]+=1
        self.fixture.options.anchor_projection_sha256=T.dump(self.root/"anchors.json",self.fixture.anchors)

    def test_exact_dna_anchor_multiple_source_roles_retained_without_duplicate_credit(self):
        source=self.fixture.source_rows[3]
        self.add_exact_anchor(source,"ROLE_A");self.add_exact_anchor(source,"ROLE_B")
        report=self.run_pipeline()
        self.assertEqual("COMPLETE",report["status"],report)
        self.assertEqual(200,report["selectedAlleles"])
        anchors=output_rows(self.root,"preserved-anchors.jsonl")
        self.assertEqual([True,True],[a["selected"] for a in anchors])
        selected=output_rows(self.root,"selected-panel.jsonl")
        matching=[r for r in selected if r["canonicalKey"]==source["canonicalKey"]]
        self.assertEqual(1,len(matching));self.assertEqual(["ROLE_A","ROLE_B"],matching[0]["matchingAnchorIds"])

    def test_exact_anchor_outside_pool_kept_unresolved(self):
        self.add_exact_anchor(row(allele(999,"A","C")),"OUTSIDE_POOL")
        report=self.run_pipeline()
        self.assertEqual("INCOMPLETE",report["status"],report)
        self.assertEqual(200,report["selectedAlleles"])
        self.assertEqual(1,report["unreconciledAnchors"])
        self.assertTrue(report["quotaComplete"])

    def test_source_freeze_enumeration_cannot_drop_prior_source(self):
        self.fixture.anchors["sourceFiles"]=[]
        self.fixture.options.anchor_projection_sha256=T.dump(self.root/"anchors.json",self.fixture.anchors)
        report=self.run_pipeline()
        self.assertEqual("FAILED",report["status"])
        self.assertEqual("ANCHOR_SOURCE_UNIVERSE_MISMATCH",report["reasonCode"])

    def test_wrong_numeric_geneid_is_unresolved_with_same_symbol(self):
        r=self.fixture.source_rows[3]
        r["source"]["maskedProjection"]["GENEINFO"]="GENE1:999"
        r["memberships"][0]["sourceGeneInfoPairs"][0]["ncbiGeneId"]="999"
        self.fixture.write_sources();report=self.run_pipeline()
        self.assertEqual("INCOMPLETE",report["status"],report)
        self.assertEqual(1,report["unresolvedCandidateContexts"])

    def test_reviewed_gene_bridge_cannot_substitute_model_rna_source(self):
        self.fixture.gene_bridge["records"][0]["source"]["sha256"]="9"*64
        self.fixture.options.geneinfo_bridge_sha256=T.dump(self.root/"gene-bridge.json",self.fixture.gene_bridge)
        report=self.run_pipeline()
        self.assertEqual("FAILED",report["status"])
        self.assertEqual("GENEINFO_BRIDGE_SOURCE_MODEL_MISMATCH",report["reasonCode"])

    def test_cancelled_write_closes_handle_and_keeps_nonfinal_manifest(self):
        original=N.Artifacts.open
        import contextlib
        @contextlib.contextmanager
        def cancel(store,path,**kwargs):
            with original(store,path,**kwargs) as writer:
                if Path(path).name=="selected-panel.jsonl.gz":
                    writer.write(b"SYNTHETIC_CANCELLED_PREFIX\n")
                    raise KeyboardInterrupt()
                yield writer
        with patch.object(N.Artifacts,"open",cancel):report=self.run_pipeline()
        self.assertEqual("CANCELLED",report["status"],report)
        self.assertFalse(report["selectedRecordsFinal"])
        self.assertFalse(report["allSourceRecordsAccountedFor"])
        entry=next(e for e in report["artifactIdentities"] if e["file"]=="selected-panel.jsonl.gz")
        self.assertFalse(entry["encodingComplete"])
        self.assertNotIn("decoded",entry)
        (self.root/"output"/"selected-panel.jsonl.gz").rename(self.root/"output"/"retained-cancelled-prefix.jsonl.gz")

    def test_existing_output_directory_preserved(self):
        (self.root/"output").mkdir();sentinel=self.root/"output"/"user-file";sentinel.write_bytes(b"KEEP")
        with self.assertRaisesRegex(ValueError,"NEW_TASK_PRIVATE_OUTPUT_DIRECTORY_REQUIRED"):self.run_pipeline()
        self.assertEqual(b"KEEP",sentinel.read_bytes())

    def test_output_cap_keeps_failure_not_prefix_complete(self):
        with patch.object(S,"MAX_OUTPUT",1048577):report=self.run_pipeline()
        self.assertEqual("FAILED",report["status"],report)
        self.assertFalse(report["panelComplete"])
        self.assertFalse(report["selectedRecordsFinal"])


def resolved_anchor_fixture(fixture, outside_allele):
    """Invented fifteen original roles and two originally protein-only roles."""
    prior=copy.deepcopy(fixture.anchors)
    source=fixture.source_rows[0]
    for i in range(15):
        prior["records"].append(dict(anchorId="OLD_ROLE_"+str(i),**{k:context()[k] for k in ("geneSymbol","hgncId","mondoId","moi")},
            originalAllele=source["original"],originalSourceKey=source["sourceAlleleKey"],sourceFile="synthetic-prior.json",sourceFileSha256="f"*64,
            sourceRecordLocator="original row "+str(i),originalFixedAt="2026-01-01",exposureRole="ORIGINAL_SYNTHETIC_ROLE",
            criterionExposureRoles={"PS3":"DEVELOPER_ORIGINAL"},sourceRole="FROZEN_PUBLIC_ANCHOR"))
    for identifier,gene in (("RESOLVED_IN_FRAME","GENE1"),("RESOLVED_OUTSIDE","OUTSIDE")):
        c=context() if gene=="GENE1" else dict(geneSymbol=gene,hgncId=None,mondoId=None,moi="ORIGINAL_MOI")
        prior["unresolvedAnchors"].append(dict(anchorId=identifier,**{k:c[k] for k in ("geneSymbol","hgncId","mondoId","moi")},
            sourceFile="synthetic-prior.json",sourceFileSha256="f"*64,sourceRecordLocator="original protein "+identifier,originalFixedAt="2026-01-01",
            exposureRole="ORIGINAL_SYNTHETIC_ROLE",criterionExposureRoles={"PS3":"DEVELOPER_ORIGINAL"},sourceRole="FROZEN_PUBLIC_ANCHOR",
            originalProteinIdentity="p.Arg3Leu",reason="ORIGINAL_PROTEIN_ONLY"))
    prior["sourceCoverage"][0].update(exactDnaRoleRecordCount=15,unresolvedProteinAnchorCount=2)
    prior["review"]["exactDnaIdentityComplete"]=False
    current=copy.deepcopy(prior)
    current.update(schemaVersion="masked-frozen-anchor-identities-v2",supplementaryRecords=[],identitySources=[],identityResolutions=[],unresolvedAnchors=[])
    current["identitySources"]=[dict(sourceId="SYNTHETIC_IDENTITY_SOURCE",relativePath="invented-identity.json",url="https://example.invalid/synthetic",version="1",sha256="9"*64)]
    for old,a in zip(prior["unresolvedAnchors"],[fixture.source_rows[3]["original"],outside_allele]):
        record={k:v for k,v in old.items() if k not in ("reason","originalProteinIdentity")}
        key=N.canonical_key(a["contig"],a["position"],a["reference"],a["alternate"])
        rid="RESOLUTION_"+old["anchorId"]
        record.update(originalAllele=a,originalSourceKey=key,identityResolutionId=rid)
        current["records" if old["geneSymbol"]=="GENE1" else "supplementaryRecords"].append(record)
        current["identityResolutions"].append(dict(resolutionId=rid,anchorId=old["anchorId"],originalProteinIdentity=old["originalProteinIdentity"],
            resolvedAt="2026-02-01",reviewer="SYNTHETIC",sourceId="SYNTHETIC_IDENTITY_SOURCE",sourceLocator="synthetic identity only",
            transcript="NM_999001.1",protein="NP_999001.1",cdnaHgvs="c.8G>T",proteinHgvs=old["originalProteinIdentity"],
            genomicHgvs=f'NC_000001.11:g.{a["position"]}{a["reference"]}>{a["alternate"]}',originalAllele=a,originalSourceKey=key,
            status="PUBLIC_SOURCE_EXACT_DNA_IDENTITY_REVIEWED",referenceValidationStatus="PENDING",normalizationStatus="PENDING",mechanicalStatus="PENDING"))
    current["sourceCoverage"][0].update(exactDnaRoleRecordCount=17,unresolvedProteinAnchorCount=0)
    current["review"]["exactDnaIdentityComplete"]=True
    fixture.prior_anchors=prior
    fixture.anchors=current


class RevisionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="synthetic-stratification-v2-")
        self.root=Path(self.temp.name);self.fixture=PipelineFixture(self.root)
    def tearDown(self):self.temp.cleanup()
    def run_pipeline(self):
        self.fixture.prepare_v2_inputs()
        return S.execute(self.fixture.options)

    def test_original_fifteen_objects_and_roles_survive_two_source_resolutions(self):
        resolved_anchor_fixture(self.fixture,allele(683,"C","T"))
        original=copy.deepcopy(self.fixture.prior_anchors["records"])
        report=self.run_pipeline()
        self.assertEqual("INCOMPLETE",report["status"],report)
        self.assertTrue(report["quotaComplete"] and report["panelComplete"] and report["selectedRecordsFinal"])
        self.assertEqual(200,report["selectedAlleles"]);self.assertFalse(report["allFrozenAnchorsReconciled"])
        self.assertEqual((17,1,0),(report["frozenAnchorRecords"],report["unreconciledAnchors"],report["panelBlockingAnchors"]))
        anchors=output_rows(self.root,"preserved-anchors.jsonl")
        by_id={r["anchorId"]:r for r in anchors}
        for old in original:self.assertEqual(old,{k:by_id[old["anchorId"]][k] for k in old})
        outside=by_id["RESOLVED_OUTSIDE"]
        self.assertIsNone(outside["canonicalKey"]);self.assertIsNone(outside["hgncId"]);self.assertIsNone(outside["mondoId"])
        self.assertEqual("ORIGINAL_MOI",outside["moi"]);self.assertEqual(0,outside["publicPanelQuotaCredit"])
        self.assertEqual("OUTSIDE_FRAME_DISJOINT_MODEL_SPANS_IDENTITY",outside["reconciliationStatus"])
        self.assertEqual(20,len(outside["disjointnessProof"]["modelSpans"]))
        self.assertTrue(all(outside[k]=="PENDING" for k in ("referenceValidationStatus","normalizationStatus","mechanicalStatus")))
        self.assertEqual(1,len(report["remainingSupplementaryReasons"]))
        self.assertEqual(20,report["sourceDiscoveryExposure"]["matchingSelectedAlleles"])

    def test_same_contig_point_in_physical_span_blocks_panel_even_outside_named_gene(self):
        resolved_anchor_fixture(self.fixture,allele(250,"A","T")) # intron in GENE1 span
        report=self.run_pipeline()
        self.assertEqual("INCOMPLETE",report["status"],report);self.assertTrue(report["quotaComplete"])
        self.assertFalse(report["panelComplete"]);self.assertEqual(1,report["panelBlockingAnchors"])

    def test_disjoint_operator_covers_endpoints_all_models_utrs_introns_and_missing(self):
        contexts=[context(i) for i in range(1,21)]
        models={c["geneSymbol"]:S.Model(model(i),c) for i,c in enumerate(contexts,1)}
        anchor=dict(originalAllele=None,identityResolutionEvidence={"reviewed":"SYNTHETIC"})
        for pos,expected in ((99,True),(100,False),(250,False),(682,False),(683,True)):
            anchor["originalAllele"]=allele(pos,"C","T")
            self.assertEqual(expected,S.supplementary_disjoint_proof(anchor,contexts,models)["status"].startswith("OUTSIDE_FRAME_DISJOINT"))
        other=model(2);other["contig"]="1"
        for e in other["exons"]:e["genomicStart"]+=1;e["genomicEnd"]+=1
        models["GENE2"]=S.Model(other,context(2));anchor["originalAllele"]=allele(683,"C","T")
        self.assertEqual("SUPPLEMENTARY_PENDING",S.supplementary_disjoint_proof(anchor,contexts,models)["status"])
        utr=model();utr["exons"].insert(0,dict(ordinal=1,genomicStart=50,genomicEnd=59,cdsStart=-9,cdsEnd=0))
        for i,e in enumerate(utr["exons"],1):e["ordinal"]=i
        models["GENE1"]=S.Model(utr,context());anchor["originalAllele"]=allele(55,"C","T")
        self.assertEqual("SUPPLEMENTARY_PENDING",S.supplementary_disjoint_proof(anchor,contexts,models)["status"])
        for a in (allele(683,"C","CT"),allele(9,"C","T","X")):
            anchor["originalAllele"]=a
            if a["contig"]=="X":models.pop("GENE20")
            self.assertEqual("SUPPLEMENTARY_PENDING",S.supplementary_disjoint_proof(anchor,contexts,models)["status"])

    def test_prior_exact_object_cannot_change_or_disappear(self):
        resolved_anchor_fixture(self.fixture,allele(683,"C","T"))
        self.fixture.anchors["records"][0]["originalFixedAt"]="2026-02-01"
        report=self.run_pipeline()
        self.assertEqual("FAILED",report["status"]);self.assertEqual("ORIGINAL_EXACT_ANCHOR_CHANGED",report["reasonCode"])

    def test_resolved_source_cannot_upgrade_original_moi_or_prior_validation(self):
        resolved_anchor_fixture(self.fixture,allele(683,"C","T"))
        self.fixture.anchors["supplementaryRecords"][0]["moi"]="LATER_SOURCE_MOI"
        report=self.run_pipeline();self.assertEqual("FAILED",report["status"])
        self.assertEqual("IDENTITY_RESOLUTION_CHANGED_ORIGINAL_ROLE_OR_ALLELE",report["reasonCode"])

    def test_resolution_reference_claim_or_unknown_condition_rejected(self):
        resolved_anchor_fixture(self.fixture,allele(683,"C","T"))
        self.fixture.anchors["identityResolutions"][0]["referenceValidationStatus"]="MATCH"
        report=self.run_pipeline();self.assertEqual("FAILED",report["status"])
        self.assertEqual("IDENTITY_RESOLUTION_NOT_SOURCE_ONLY",report["reasonCode"])

    def test_versioned_hgvs_prefix_must_match_separate_versioned_transcript(self):
        resolved_anchor_fixture(self.fixture,allele(683,"C","T"))
        self.fixture.anchors["identityResolutions"][0]["cdnaHgvs"]="NM_999001.2:c.8G>T"
        report=self.run_pipeline();self.assertEqual("FAILED",report["status"])
        self.assertEqual("IDENTITY_RESOLUTION_NOT_SOURCE_ONLY",report["reasonCode"])

    def test_unknown_identity_resolution_field_cannot_be_ignored(self):
        resolved_anchor_fixture(self.fixture,allele(683,"C","T"))
        self.fixture.anchors["identityResolutions"][0]["additionalClinicalApproval"]=True
        report=self.run_pipeline();self.assertEqual("FAILED",report["status"])
        self.assertEqual("UNKNOWN_OR_MISSING_FIELD",report["reasonCode"])

    def test_projection_missing_probe_cannot_authorize_prefix_final(self):
        self.fixture.prepare_v2_inputs()
        value=json.loads(self.fixture.options.source_discovery_projection.read_bytes());value["records"].pop()
        self.fixture.options.source_discovery_projection_sha256=T.dump(self.fixture.options.source_discovery_projection,value)
        report=self.run_pipeline();self.assertEqual("FAILED",report["status"])
        self.assertFalse(report["selectedRecordsFinal"]);self.assertFalse((self.root/"output"/"selected-panel.jsonl.gz").exists())

    def test_projected_allele_cannot_change_even_when_projection_rehashed(self):
        self.fixture.prepare_v2_inputs()
        value=json.loads(self.fixture.options.source_discovery_projection.read_bytes());value["records"][0]["allele"]["alternate"]="A"
        self.fixture.options.source_discovery_projection_sha256=T.dump(self.fixture.options.source_discovery_projection,value)
        report=self.run_pipeline();self.assertEqual("FAILED",report["status"])
        self.assertEqual("SOURCE_DISCOVERY_PROJECTION_OR_REFERENCE_MISMATCH",report["reasonCode"])

    def test_original_vcf_hash_and_archived_ref_proof_both_enforced(self):
        self.fixture.prepare_v2_inputs()
        value=json.loads(self.fixture.options.source_discovery_prepare.read_bytes());value["probes"][0]["referenceRead"]["observedOriginalByteHex"]="41"
        projection=json.loads(self.fixture.options.source_discovery_projection.read_bytes())
        projection["bindings"]["probeManifestSha256"]=T.dump(self.fixture.options.source_discovery_prepare,value)
        projection["records"][0]["sourceLocators"]["referenceRead"]["observedOriginalByteHex"]="41"
        self.fixture.options.source_discovery_projection_sha256=T.dump(self.fixture.options.source_discovery_projection,projection)
        report=self.run_pipeline();self.assertEqual("FAILED",report["status"])
        self.assertEqual("SOURCE_DISCOVERY_ARCHIVED_REFERENCE_MISMATCH",report["reasonCode"])

    def test_original_soft_mask_case_is_preserved_and_compared_as_one_dna_base(self):
        for raw,reference,expected in (("63","C",True),("74","T",True),("67","C",False),("ff","C",False),
                ("4e","C",False),("4343","C",False),("0x43","C",False),("","C",False)):
            with self.subTest(raw=raw,reference=reference):
                self.assertEqual(expected,S.archived_reference_byte_matches(raw,reference))
        self.fixture.prepare_v2_inputs()
        value=json.loads(self.fixture.options.source_discovery_prepare.read_bytes())
        value["probes"][0]["referenceRead"]["observedOriginalByteHex"]="63"
        projection=json.loads(self.fixture.options.source_discovery_projection.read_bytes())
        projection["bindings"]["probeManifestSha256"]=T.dump(self.fixture.options.source_discovery_prepare,value)
        projection["records"][0]["sourceLocators"]["referenceRead"]["observedOriginalByteHex"]="63"
        self.fixture.options.source_discovery_projection_sha256=T.dump(self.fixture.options.source_discovery_projection,projection)
        report=self.run_pipeline();self.assertEqual("COMPLETE",report["status"],report)
        selected=output_rows(self.root,"selected-panel.jsonl")
        matched=next(r for r in selected if "SYNTHETIC_PROBE_1" in r["sourceDiscoveryOverlap"]["matchedProbeIds"])
        self.assertEqual("63",matched["sourceDiscoveryOverlap"]["matchingProbeRecords"][0]["sourceLocators"]["referenceRead"]["observedOriginalByteHex"])

    def test_probe_exposure_changes_trace_only_and_never_rank_or_original_roles(self):
        self.fixture.prepare_v2_inputs()
        inputs=S.Inputs();o=self.fixture.options
        discovery=S.load_discovery(inputs,o.source_discovery_projection,o.source_discovery_projection_sha256,o.source_discovery_prepare,o.source_discovery_vcf)
        contexts=[context(i) for i in range(1,21)]
        models={c["geneSymbol"]:S.Model(model(i),c) for i,c in enumerate(contexts,1)}
        groups={r["canonicalKey"]:[r] for r in self.fixture.source_rows}
        known=S.assemble(contexts,models,self.fixture.source_rows,groups,[],discovery=discovery)
        unknown=S.assemble(contexts,models,self.fixture.source_rows,groups,[])
        self.assertEqual([r["canonicalKey"] for r in unknown["selected"]],[r["canonicalKey"] for r in known["selected"]])
        self.assertEqual(unknown["hashOrders"],known["hashOrders"])
        self.assertEqual(20,sum(r["sourceDiscoveryOverlap"]["status"]=="MATCHED" for r in known["assignments"]))
        for r in known["assignments"]:
            trace=r["sourceDiscoveryOverlap"]
            self.assertEqual("NONE",trace["selectionEffect"]);self.assertFalse(trace["freshReferenceRead"])
            self.assertFalse(trace["priorRuleOrClinicalResultClaim"])
        key=self.fixture.source_rows[0]["canonicalKey"]
        self.assertEqual("NO_MATCH_IN_COMPLETE_PINNED_PROBE_SET",S.discovery_overlap(key.replace("|T","|A"),discovery)["status"])


class SerializationTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="synthetic-gzip-stratification-")
        self.root=Path(self.temp.name)

    def tearDown(self):self.temp.cleanup()

    def write(self, rows, directory="first", maximum=1048576, reserve=1024):
        output=self.root/directory;output.mkdir()
        store=N.Artifacts(output,maximum,reserve);identities=[]
        S.write_gzip_jsonl(store,output,"source-accounting.jsonl",iter(rows),identities)
        return output,store,identities[0]

    def test_lossless_canonical_utf8_lf_and_deterministic_filename_free_gzip(self):
        rows=[{"id":"SYNTHETIC","literal":"中文\\n\n雪","empty":None,"records":[1,2,1]}]*300
        one,store,identity=self.write(rows)
        two,_,other=self.write(rows,"different-directory")
        raw=(one/identity["file"]).read_bytes()
        self.assertEqual(raw,(two/other["file"]).read_bytes())
        self.assertEqual(b"\x1f\x8b\x08\x00\x00\x00\x00\x00",raw[:8])
        expected=b"".join(S.canonical(row)+b"\n" for row in rows)
        self.assertEqual(expected,gzip.decompress(raw))
        self.assertEqual(dict(bytes=len(expected),sha256=S.sha(expected),rowCount=300),identity["decoded"])
        self.assertEqual(len(raw),store.used)
        self.assertEqual(identity,other)

    def test_compressed_budget_can_preserve_decoded_records_larger_than_budget(self):
        rows=[{"repeated":"SYNTHETIC"*1000,"i":i} for i in range(600)]
        output,store,identity=self.write(rows,maximum=60000)
        self.assertGreater(identity["decoded"]["bytes"],store.maximum)
        self.assertLess(store.used,store.maximum-store.reserve)
        self.assertEqual(600,identity["decoded"]["rowCount"])
        self.assertEqual(identity["decoded"],S.verify_gzip_jsonl(output/identity["file"],identity["decoded"])["decoded"])

    def test_empty_artifact_is_complete_zero_decoded_rows(self):
        output,_,identity=self.write([])
        self.assertTrue(identity["encodingComplete"])
        self.assertEqual(b"",gzip.decompress((output/identity["file"]).read_bytes()))
        self.assertEqual(dict(bytes=0,sha256=S.sha(b""),rowCount=0),identity["decoded"])

    def test_logical_locator_resolves_exact_verified_source_record_ids(self):
        rows=[{"recordId":"R2","value":"second"},{"recordId":"R1","value":"first"}]
        output,_,identity=self.write(rows)
        manifest={"artifactIdentities":[identity]}
        path=S.resolve_jsonl_artifact(output,manifest,"source-accounting.jsonl")
        self.assertEqual(rows,[json.loads(line) for line in gzip.decompress(path.read_bytes()).splitlines()])
        for change in ({"encodingComplete":False},{"file":"../source-accounting.jsonl.gz"},{"encoding":"plain"}):
            with self.subTest(change=change),self.assertRaises(ValueError):
                S.resolve_jsonl_artifact(output,{"artifactIdentities":[dict(identity,**change)]},"source-accounting.jsonl")
        with self.assertRaisesRegex(ValueError,"NOT_UNIQUE"):
            S.resolve_jsonl_artifact(output,{"artifactIdentities":[identity,identity]},"source-accounting.jsonl")

    def test_truncated_crc_second_member_and_trailing_garbage_are_never_verified(self):
        output,_,identity=self.write([{"id":"SYNTHETIC"}]);path=output/identity["file"]
        original=path.read_bytes()
        corrupt=bytearray(original);corrupt[-8]^=1
        for raw in (original[:-1],bytes(corrupt),original+b"garbage",original+gzip.compress(b"",mtime=0)):
            with self.subTest(rawLength=len(raw)):
                path.write_bytes(raw)
                entry=dict(identity,bytes=len(raw),sha256=S.sha(raw))
                with self.assertRaises((ValueError,S.zlib.error)):
                    S.resolve_jsonl_artifact(output,{"artifactIdentities":[entry]},"source-accounting.jsonl")

    def test_physical_and_decoded_identities_both_required(self):
        output,_,identity=self.write([{"id":"SYNTHETIC"}])
        variants=[dict(identity,sha256="0"*64),dict(identity,bytes=identity["bytes"]+1)]
        for key,value in (("bytes",identity["decoded"]["bytes"]-1),("sha256","0"*64),("rowCount",2)):
            variants.append(dict(identity,decoded=dict(identity["decoded"],**{key:value})))
        for entry in variants:
            with self.subTest(entry=entry),self.assertRaises(ValueError):
                S.resolve_jsonl_artifact(output,{"artifactIdentities":[entry]},"source-accounting.jsonl")

    def test_trailer_budget_failure_retains_unverified_prefix_and_closed_handle(self):
        rows=[{"id":"SYNTHETIC"}]
        _,_,reference=self.write(rows)
        output=self.root/"bounded";output.mkdir();identities=[]
        store=N.Artifacts(output,reference["bytes"]+1023,1024)
        with self.assertRaisesRegex(ValueError,"OUTPUT_ARTIFACT_BUDGET_EXHAUSTED"):
            S.write_gzip_jsonl(store,output,"source-accounting.jsonl",iter(rows),identities)
        self.assertFalse(identities[0]["encodingComplete"])
        self.assertNotIn("decoded",identities[0])
        self.assertLessEqual(store.used,store.maximum-store.reserve)
        path=output/identities[0]["file"]
        self.assertLess(path.stat().st_size,reference["bytes"])
        path.rename(output/"retained-prefix.gz")

    def test_cancelled_rows_keep_primary_cancel_when_gzip_close_also_fails(self):
        output=self.root/"cancelled";output.mkdir();store=N.Artifacts(output,1048576,1024);identities=[]
        def interrupted():
            yield {"id":"SYNTHETIC"}
            raise KeyboardInterrupt("ORIGINAL_CANCEL")
        close=gzip.GzipFile.close
        def failed_close(zipped):
            active=zipped.fileobj is not None
            close(zipped)
            if active:raise ValueError("SECONDARY_CLOSE_FAILURE")
        with patch.object(gzip.GzipFile,"close",failed_close),self.assertRaisesRegex(KeyboardInterrupt,"ORIGINAL_CANCEL"):
            S.write_gzip_jsonl(store,output,"source-accounting.jsonl",interrupted(),identities)
        self.assertFalse(identities[0]["encodingComplete"])
        self.assertNotIn("decoded",identities[0])
        (output/identities[0]["file"]).rename(output/"retained-cancelled.gz")

    def test_pipeline_cancel_after_closed_gzip_still_cannot_publish_final_panel(self):
        fixture=PipelineFixture(self.root);fixture.prepare_v2_inputs()
        verify=S.verify_gzip_jsonl
        def cancelled(path,expected):
            value=verify(path,expected)
            if path.name=="selected-panel.jsonl.gz":raise KeyboardInterrupt("AFTER_VERIFIED_STREAM")
            return value
        with patch.object(S,"verify_gzip_jsonl",cancelled):report=S.execute(fixture.options)
        self.assertEqual("CANCELLED",report["status"])
        self.assertFalse(report["panelComplete"]);self.assertFalse(report["selectedRecordsFinal"])
        entry=next(item for item in report["artifactIdentities"] if item["file"]=="selected-panel.jsonl.gz")
        self.assertFalse(entry["encodingComplete"]);self.assertNotIn("decoded",entry)
        self.assertEqual(report,json.loads((self.root/"output"/"manifest.json").read_text()))


if __name__ == "__main__":
    unittest.main()
