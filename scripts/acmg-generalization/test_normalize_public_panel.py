"""Synthetic-only oracles; VARVIS_TEST_BCFTOOLS selects an existing local binary."""
import copy
import hashlib
import importlib.util
import inspect
import json
import os
import sys
from pathlib import Path
import tempfile
import types
import unittest

SPEC = importlib.util.spec_from_file_location("normalizer", Path(__file__).with_name("normalize_public_panel.py"))
N = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(N)
POLICY = dict(initialWindowSource="QUERY_PLAN_V2_WINDOWS", source="synthetic",
    originalQueryWindowCount=1, originalTotalBases=101, originalMaximumWindowBases=25000,
    initialRightPaddingBases=49, cumulativeLeftExtensions=[0, 1000, 10000], maximumLeftExtensionBases=10000,
    maximumRightRefCompletionBases=49, maximumWindowBases=35049, maximumAttemptsPerWindow=3,
    maximumWindowReads=387, maximumTotalFastaBases=12582912, clipOnlyToActualContigBounds=True,
    grouping="synthetic", extensionTrigger="synthetic", noFurtherExtension="synthetic", budgetRationale="synthetic")


def dump(path, value):
    content = N.canonical(value) + b"\n"
    path.write_bytes(content)
    return N.sha(content)


class Fixture:
    def __init__(self, directory, sequence, start=100, end=200):
        self.root = directory
        self.windows = {"W1": dict(contig="1", start=start, end=end, geneSymbol="GENE", queryRegion=f"1:{start}-{end}", windowOrdinal=1)}
        self.policy = dict(POLICY, originalTotalBases=end-start+1)
        fasta = b">chr1\n" + b"\n".join(sequence[i:i+60].encode() for i in range(0, len(sequence), 60)) + b"\n"
        (directory / "ref.fa").write_bytes(fasta)
        index = f"chr1\t{len(sequence)}\t6\t{min(len(sequence),60)}\t{min(len(sequence),60)+1}\n".encode()
        (directory / "ref.fa.fai").write_bytes(index)
        self.alias = directory / "aliases.tsv"
        self.alias.write_bytes(b"1\tchr1\n")
        stat = (directory / "ref.fa").stat()
        actual = dict(resourceId="SYNTHETIC_REFERENCE", resourceVersion="SYNTHETIC", assembly="GRCh38", logicalName="ref.fa",
            expectedSha256=N.sha(fasta), observedSha256=N.sha(fasta), hashAlgorithm="SHA-256", hashScope="RAW_BYTES",
            hashStatus="VERIFIED", unchangedDuringHash=True, byteLength=len(fasta), mtimeNs=stat.st_mtime_ns,
            indexSha256=N.sha(index), startedAt="2026-01-01T00:00:00Z", completedAt="2026-01-01T00:00:01Z")
        self.actual = actual
        self.receipt = dict(source="SYNTHETIC", version="1", records={"reference":dict(kind="REFERENCE_IDENTITY", assembly="GRCh38",
            resourceDataClass="PUBLIC", verificationStatus="VERIFIED", verificationMethod="SHA-256", observedSha256=N.sha(fasta),
            expectedSha256=N.sha(fasta), referenceArtifactLocator="ref.fa", actualChecksumReceipt=actual)})
        self.receipt_path = directory / "receipt.json"
        self.receipt_sha = dump(self.receipt_path, self.receipt)
        self.ref_sha, self.alias_sha = N.sha(fasta), N.sha(self.alias.read_bytes())

    def reference(self):
        return N.BoundedReference(self.root, self.receipt_path, self.receipt_sha, "SYNTHETIC", "1", "reference",
                                  self.ref_sha, self.alias, self.alias_sha, self.windows, self.policy)

    def candidates(self, alleles):
        result = []
        for i, (position, ref, alt) in enumerate(alleles, 1):
            raw = N.sha(str((position, ref, alt, i)).encode())
            projection = dict(CHROM="1", POS=position, ID=str(i), REF=ref, ALT=alt, FILTER=".",
                              sourceRawLineSha256=raw, sourceSnapshotIdentity="a"*64, GENEINFO="GENE:1|OTHER:2")
            member = dict(sourceGeneInfoPairs=[dict(symbol="GENE", ncbiGeneId="1"), dict(symbol="OTHER", ncbiGeneId="2")],
                geneMembershipStatus="MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED", alleleEnvelopeStatus="ELIGIBLE_IDENTITY_ONLY",
                membershipStatus="MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED", samplingEligibility="EXCLUDED_OR_SOURCE_REVIEW_REQUIRED",
                sourceRawLineSha256=raw, queryGeneSymbol="GENE", queryRegion=self.windows["W1"]["queryRegion"], queryReturnOrdinal=i,
                occurrenceLineNumber=i)
            result.append(dict(id=f"R{i}", windowId="W1", position=position, ref=ref, alt=alt,
                original=dict(assembly="GRCh38", contig="1", position=position, reference=ref, alternate=alt),
                source=dict(maskedProjection=projection, projectionLineNumber=i, sourceAlleleKey=N.canonical_key("1",position,ref,alt)),
                memberships=[member]))
        return result


def source_inputs(fixture, binary=None):
    root = fixture.root
    window = fixture.windows["W1"]
    candidates = fixture.candidates([(150,"A","C"),(180,"A","C")])
    query = dict(schemaVersion="prospective-local-public-allele-query-plan-v2", frameBinding=dict(fileSha256="f"*64),
        totals=dict(queryWindows=1, queriedBasesIncludingPadding=window["end"]-window["start"]+1),
        sources=[dict(assembly="GRCh38", sourceId="SYNTHETIC", wholeDataSha256=None)],
        geneQueries=[dict(geneSymbol="GENE", sourceGeneInterval=dict(assembly="GRCh38",chromosome="1"),
            queryWindows=[dict(start=window["start"],end=window["end"],tabixRegion=window["queryRegion"])],
            queryPartitions=[dict(partitionId="ONE", firstWindowOrdinal=1,lastWindowOrdinal=1,maximumRowObservations=150000,maximumRawUtf8Bytes=1048576)])])
    query_sha = dump(root/"query.json",query)
    projections = [item["source"]["maskedProjection"] for item in candidates]
    occurrences = [dict(item["memberships"][0]) for item in candidates]
    for row in occurrences:
        del row["occurrenceLineNumber"]
    contents = {"sampling-projection.jsonl":b"".join(N.canonical(row)+b"\n" for row in projections),
                "query-occurrences.jsonl":b"".join(N.canonical(row)+b"\n" for row in occurrences)}
    for name, value in contents.items():
        (root/name).write_bytes(value)
    manifest = dict(schemaVersion="local-public-allele-pool-manifest-v1",status="COMPLETE",samplingAllowed=True,
        sourceIdentityVerified=True,classificationFieldsExposedToSampler=False,referenceNormalizationPerformed=False,
        planSha256=query_sha,sourceRawLineHashConvention="SHA256_ORIGINAL_UTF8_BYTES_EXCLUDING_LINE_TERMINATOR",
        sourceSnapshotIdentity=query["sources"][0], sourceSnapshotIdentitySha256="a"*64,
        frameBinding=query["frameBinding"],plannedTotals=query["totals"],networkCalls=0,providerCalls=0,criterionEvaluations=0,selectedAlleles=0,
        uniqueRawRows=2,retainedRowObservations=2,genes=[dict(geneSymbol="GENE",status="COMPLETE",
            windows=[dict(queryRegion=window["queryRegion"],status="COMPLETE",retainedObservations=2)],
            partitions=[dict(query["geneQueries"][0]["queryPartitions"][0],status="COMPLETE")])],
        artifacts=[dict(file=name,bytes=len(value),sha256=N.sha(value)) for name,value in contents.items()])
    manifest_sha = dump(root/"pool.json",manifest)
    definition = dict(assembly="GRCh38",resourceVersion="SYNTHETIC",logicalFastaPath="ref.fa",fastaBytes=fixture.actual["byteLength"],
        fastaMtimeNs=fixture.actual["mtimeNs"],logicalFaiPath="ref.fa.fai",faiBytes=(root/"ref.fa.fai").stat().st_size,
        faiSha256=fixture.actual["indexSha256"],wholeFastaSha256=fixture.ref_sha,identityBoundary="synthetic full-file receipt",
        checksumReceipt=dict(logicalPath="receipt.json",fileSha256=fixture.receipt_sha,locator="records.reference.actualChecksumReceipt",
            resourceId="SYNTHETIC_REFERENCE",hashScope="RAW_BYTES",hashStatus="VERIFIED",completedAt=fixture.actual["completedAt"],boundary="synthetic"))
    plan = dict(schemaVersion="public-panel-reference-normalization-plan-v1",planId="SYNTHETIC",version="1",status="PROSPECTIVELY_FIXED_BEFORE_REFERENCE_READS",
        reviewer="synthetic",scope="synthetic",bindings=dict(frameSha256="f"*64,queryPlanSha256=query_sha,poolManifestSha256=manifest_sha,
        maskedProjectionSha256=N.sha(contents["sampling-projection.jsonl"]),queryOccurrencesSha256=N.sha(contents["query-occurrences.jsonl"]),
        provisionalPrerankManifestSha256="b"*64),reference=definition,
        normalizer=dict(name="bcftools",expectedVersion="1.23.1",logicalExecutablePath="bcftools",executableBytes=Path(binary).stat().st_size if binary else 1,
            executableSha256=N.sha(Path(binary).read_bytes()) if binary else "a"*64,reuse="synthetic",requiredRefCheck="e",inputEnvelope="synthetic",commandBoundary="synthetic"),
        queryPlanSha256=query_sha,windowPolicy=fixture.policy,
        alleleEnvelope=dict(assembly="GRCh38",maximumAlleleLength=50,allow=["SNV","SIMPLE_ANCHORED_INSERTION","SIMPLE_ANCHORED_DELETION"],splitMultiallelic=False),
        execution=dict(engine="bcftools norm",version="1.23.1",htslibVersion="1.23.1",sourceRead="MASKED_PROJECTION_AND_QUERY_OCCURRENCES_ONLY",panelSelection=False,criterionEvaluation=False,versionBoundary="synthetic"),
        budgets=dict(maximumInputArtifactBytes=268435456,maximumOutputArtifactBytes=268435456,manifestReserveBytes=1048576,
            maximumInputRowsPerArtifact=150000,maximumSingleInputLineBytes=1048576,rowAccounting="separate",byteAccounting="cumulative",reserveRationale="synthetic"),
        normalizationSemantics={key:"synthetic" for key in "originalRef coordinateTransfer leftmostProof canonicalKey equivalence geneIdentity aliases finalRanking".split()},
        outputContract=dict(manifestSchemaVersion="public-panel-normalization-manifest-v1",recordSchemaVersion="public-source-allele-normalization-v1",
            requiredRecordFields="sourceAlleleKey sourceRawLineSha256s queryLocators geneMembershipStatuses normalizationStatus reasonCode referenceStatus canonicalAllele attemptIds".split(),
            requiredAttemptFields="attemptId originalWindowId extensionIndex assembly contig windowStart windowEnd absoluteToLocalOffset referenceSequenceSha256 referenceBasesRead commandReceipt outcome".split(),
            requiredManifestFields="planSha256 inputIdentities referenceIdentity normalizerIdentity status allSourceRecordsAccountedFor unresolvedRecords referenceBasesRead windowReads artifactIdentities finalPanelSelectedAlleles ruleEvaluations".split(),
            canonicalNullForUnresolvedOrRejectedRecord=True,deterministicSourceRejection="synthetic",incompleteHandling="synthetic",noImplicitSelection="synthetic"),
        prospectiveTestsRequired=[],executionOwnership="synthetic",normalizationOrRuleOutputsSeenToSetBudgets=False,finalSelectedAlleles=0,ruleEvaluations=0,clinicalAcceptance=False)
    plan["normalizationSemantics"]["canonicalKey"] = "GRCh38|canonicalContig|leftNormalizedPosition1|REF|ALT"
    plan_sha = dump(root/"plan.json",plan)
    options = types.SimpleNamespace(plan=root/"plan.json",plan_sha256=plan_sha,query_plan=root/"query.json",pool_manifest=root/"pool.json",
        projection=root/"sampling-projection.jsonl",occurrences=root/"query-occurrences.jsonl",resource_root=root,
        reference_receipt=fixture.receipt_path,reference_source_id="SYNTHETIC",reference_source_version="1",reference_record_locator="reference",
        aliases=fixture.alias,aliases_sha256=fixture.alias_sha,bcftools=binary,task_output_root=root,output=root/"run-output")
    return plan,query,manifest,options


class PlanInputTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix="synthetic-input-")
        self.root=Path(self.temp.name)
        self.fixture=Fixture(self.root,"A"*300)
        self.plan,self.query,self.manifest,self.options=source_inputs(self.fixture)

    def tearDown(self):
        self.temp.cleanup()

    def load(self):
        windows,lookup=N.validate_plan(self.plan,self.query)
        return N.load_candidates(self.plan,self.query,self.options.pool_manifest,self.options.projection,self.options.occurrences,windows,lookup)

    def rebind_artifact(self,name,rows):
        value=b"".join(N.canonical(row)+b"\n" for row in rows)
        (self.root/name).write_bytes(value)
        for entry in self.manifest["artifacts"]:
            if entry["file"]==name:
                entry.update(bytes=len(value),sha256=N.sha(value))
        binding="maskedProjectionSha256" if name=="sampling-projection.jsonl" else "queryOccurrencesSha256"
        self.plan["bindings"][binding]=N.sha(value)
        self.plan["bindings"]["poolManifestSha256"]=dump(self.options.pool_manifest,self.manifest)

    def test_masked_inputs_keep_all_sources_and_multiple_gene_pending(self):
        candidates,identity=self.load()
        self.assertEqual(len(candidates),2)
        self.assertEqual(identity["retainedRowObservations"],2)
        self.assertFalse(identity["classificationFieldsRead"])
        self.assertTrue(all(c["memberships"][0]["geneMembershipStatus"]=="MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED" for c in candidates))

    def test_classification_key_refused_even_after_validly_rebound_hash(self):
        rows=[json.loads(line) for line in self.options.projection.read_text().splitlines()]
        rows[0]["CLNSIG"]="synthetic-forbidden"
        self.rebind_artifact("sampling-projection.jsonl",rows)
        with self.assertRaisesRegex(ValueError,"UNKNOWN_OR_MISSING_FIELD"):
            self.load()

    def test_unknown_operational_requirement_is_not_ignored(self):
        for container in (self.plan,self.plan["windowPolicy"],self.plan["reference"],self.plan["execution"]):
            container["additionalRequirements"]={"hidden":True}
            with self.assertRaisesRegex(ValueError,"UNKNOWN_OR_MISSING_FIELD"):
                N.validate_plan(self.plan,self.query)
            del container["additionalRequirements"]

    def test_capped_peer_rejected_even_if_outer_complete(self):
        self.manifest["genes"][0]["partitions"][0]["status"]="CAPPED"
        self.plan["bindings"]["poolManifestSha256"]=dump(self.options.pool_manifest,self.manifest)
        with self.assertRaisesRegex(ValueError,"POOL_PARTITION_INCOMPLETE"):
            self.load()

    def test_missing_occurrence_or_wrong_query_not_silently_dropped(self):
        rows=[json.loads(line) for line in self.options.occurrences.read_text().splitlines()]
        rows[0]["queryRegion"]="1:1-99"
        self.rebind_artifact("query-occurrences.jsonl",rows)
        with self.assertRaisesRegex(ValueError,"OCCURRENCE_SOURCE_OR_QUERY_UNKNOWN"):
            self.load()

    def test_separate_row_caps_and_cumulative_byte_caps(self):
        # Each input has two rows; their combined four rows do not exceed a
        # per-artifact limit of two. Actual execution plan retains 150000 each.
        self.plan["budgets"]["maximumInputRowsPerArtifact"]=2
        windows,lookup=self.fixture.windows,{("GENE",self.fixture.windows["W1"]["queryRegion"]):"W1"}
        args=(self.plan,self.query,self.options.pool_manifest,self.options.projection,self.options.occurrences,windows,lookup)
        self.assertEqual(len(N.load_candidates(*args)[0]),2)
        self.plan["budgets"]["maximumInputArtifactBytes"]=len(self.options.pool_manifest.read_bytes())+len(self.options.projection.read_bytes())
        with self.assertRaisesRegex(ValueError,"INPUT_CUMULATIVE_BUDGET"):
            N.load_candidates(*args)

    def test_reference_packaging_cannot_substitute_other_whole_receipt(self):
        self.plan["reference"]["logicalFastaPath"]="other.fa"
        with self.assertRaisesRegex(ValueError,"PLAN_REFERENCE_RECEIPT_BINDING_MISMATCH"):
            N.bind_reference_plan(self.fixture.reference(),self.plan["reference"])

    def test_projection_hash_tamper_fails_before_reference_read(self):
        self.options.projection.write_bytes(self.options.projection.read_bytes()+b" ")
        with self.assertRaisesRegex(ValueError,"FILE_SIZE_LIMIT"):
            self.load()

    def test_occurrence_gap_cannot_be_hidden_by_total_counts(self):
        rows=[json.loads(line) for line in self.options.occurrences.read_text().splitlines()]
        rows[1]["queryReturnOrdinal"]=3
        self.rebind_artifact("query-occurrences.jsonl",rows)
        with self.assertRaisesRegex(ValueError,"QUERY_RETURN_SEQUENCE_INCOMPLETE"):
            self.load()


class ReferenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="synthetic-ref-")
        self.root = Path(self.temp.name)
        self.f = Fixture(self.root, "ACGT"*100)

    def tearDown(self):
        self.temp.cleanup()

    def test_accepted_full_receipt_reuses_window_and_softmask_has_own_hash(self):
        reference = self.f.reference()
        sequence, locator = reference.read("W1", 0, 249)
        self.assertEqual(sequence, ("ACGT"*100)[99:249])
        self.assertEqual(reference.read("W1", 0, 249), (sequence, locator))
        self.assertEqual(reference.reads, 1)
        self.assertFalse(reference.identity["fullReferenceHashComputed"])
        self.assertEqual(reference.identity["actualChecksumReceipt"]["hashScope"], "RAW_BYTES")
        self.assertEqual(locator["hashScope"], "QUERIED_BASES_ONLY_NOT_WHOLE_FASTA")

    def test_receipt_metadata_mutation_rejects_before_bases(self):
        reference = self.f.reference()
        with (self.root/"ref.fa").open("ab") as stream:
            stream.write(b"A")
        with self.assertRaisesRegex(ValueError, "REFERENCE_CHANGED"):
            reference.read("W1", 0, 249)
        self.assertEqual(reference.returned_bases, 0)

    def test_receipt_window_hash_cannot_be_whole_identity(self):
        self.f.actual["hashScope"] = "QUERIED_BASES"
        self.f.receipt_sha = dump(self.f.receipt_path, self.f.receipt)
        with self.assertRaisesRegex(ValueError, "FULL_CHECKSUM"):
            self.f.reference()

    def test_index_or_alias_tamper_and_wrong_receipt_pin(self):
        reference = self.f.reference()
        self.f.alias.write_bytes(b"1\tchr2\n")
        with self.assertRaisesRegex(ValueError, "SOURCE_SHA_MISMATCH"):
            reference.read("W1", 0, 249)
        self.assertEqual(reference.charged_bases, 0)

    def test_only_fixed_extensions_and_right_49_are_allowed(self):
        reference = self.f.reference()
        for extension, right in [(1,200),(10001,200),(0,250)]:
            with self.assertRaisesRegex(ValueError, "UNAUTHORIZED"):
                reference.read("W1", extension, right)
        self.assertEqual(reference.reads, 0)

    def test_read_budget_reserved_before_read(self):
        self.f.policy["maximumTotalFastaBases"] = 100
        reference = self.f.reference()
        with self.assertRaisesRegex(ValueError, "BUDGET_EXHAUSTED"):
            reference.read("W1", 0, 249)
        self.assertEqual(reference.returned_bases, 0)

    def test_softmask_preserves_raw_window_hash_without_altering_ref(self):
        # A fresh full synthetic receipt is required after changing source bytes.
        other=self.root/"softmask"
        other.mkdir()
        fixture=Fixture(other,"acgt"*100)
        reference=fixture.reference()
        sequence,locator=reference.read("W1",0,249)
        self.assertTrue(locator["softMaskCaseNormalized"])
        self.assertEqual(sequence,("ACGT"*100)[99:249])
        self.assertNotEqual(locator["rawQueriedBasesSha256"],locator["normalizedCaseBasesSha256"])

    def test_pipe_output_cap_retains_manifest_reserve_and_owned_process_exits(self):
        output=self.root/"pipe"
        output.mkdir()
        store=N.Artifacts(output,4096,1024)
        with self.assertRaisesRegex(ValueError,"OUTPUT_ARTIFACT_BUDGET_EXHAUSTED"):
            store.process([sys.executable,"-I","-S","-c","import sys; sys.stdout.buffer.write(b'x'*10000)"],
                          output,output/"stdout.txt",output/"stderr.txt",0)
        store.json("manifest.json",dict(status="FAILED",allSourceRecordsAccountedFor=False),manifest=True)
        self.assertLessEqual(sum(p.stat().st_size for p in store.paths),4096)

    def test_partial_process_start_cancellation_stops_all_owned_resources(self):
        lines,start=inspect.getsourcelines(N.Artifacts.process)
        targets={name:start+next(i for i,line in enumerate(lines) if token in line) for name,token in [
            ("after_popen","def drain("),("before_start","for thread in threads:"),("second_start","thread.start()") ]}
        for name,line in targets.items():
            with self.subTest(seam=name):
                directory=self.root/name;directory.mkdir()
                store=N.Artifacts(directory,4194304,1048576)
                captured=[]
                def cancel(frame,event,_):
                    if event=="line" and frame.f_code is N.Artifacts.process.__code__ and frame.f_lineno==line:
                        if name=="second_start" and frame.f_locals["thread"] is not frame.f_locals["threads"][1]:
                            return cancel
                        captured.append(frame.f_locals.copy())
                        raise KeyboardInterrupt("SYNTHETIC_LIFECYCLE_SEAM")
                    return cancel
                try:
                    sys.settrace(cancel)
                    with self.assertRaises(KeyboardInterrupt):
                        store.process([sys.executable,"-I","-S","-c","import time;time.sleep(20)"],directory,
                                      directory/"stdout.txt",directory/"stderr.txt",getattr(N.subprocess,"CREATE_NO_WINDOW",0))
                finally:
                    sys.settrace(None)
                state=captured[0]
                self.assertIsNotNone(state["process"].poll())
                self.assertTrue(all(not t.is_alive() for t in state.get("threads",[])))
                self.assertTrue(state["process"].stdout.closed)
                self.assertTrue(state["process"].stderr.closed)

    def test_artifact_open_cancellation_before_yield_closes_handle(self):
        wrapped=N.Artifacts.open.__wrapped__
        lines,start=inspect.getsourcelines(wrapped)
        line=start+next(i for i,value in enumerate(lines) if "yield Writer()" in value)
        captured=[]
        def cancel(frame,event,_):
            if event=="line" and frame.f_code is wrapped.__code__ and frame.f_lineno==line:
                captured.append(frame.f_locals["stream"])
                raise KeyboardInterrupt("SYNTHETIC_WRITER_HANDOFF")
            return cancel
        store=N.Artifacts(self.root,4194304,1048576)
        try:
            sys.settrace(cancel)
            with self.assertRaises(KeyboardInterrupt):
                with store.open(self.root/"cancelled.txt"):
                    self.fail("Cancelled writer cannot reach caller")
        finally:
            sys.settrace(None)
        self.assertTrue(captured[0].closed)


class ActualSyntheticBcftoolsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.binary = os.environ.get("VARVIS_TEST_BCFTOOLS")
        if not cls.binary:
            raise unittest.SkipTest("Explicit existing bcftools required; no installation performed")
        cls.binary_sha = N.sha(Path(cls.binary).read_bytes())

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="synthetic-norm-")
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def run_case(self, sequence, alleles, start=100, end=200, policy=None):
        fixture = Fixture(self.root, sequence, start, end)
        if policy:
            fixture.policy.update(policy)
        directory = self.root / "output"
        directory.mkdir()
        engine = N.BcftoolsWindowEngine(self.binary, self.binary_sha, "1.23.1", "1.23.1", directory)
        reference, candidates = fixture.reference(), fixture.candidates(alleles)
        results = N.normalize_candidates(candidates, fixture.windows, reference, engine, fixture.policy)
        return fixture, reference, engine, candidates, results

    def test_snv_at_artificial_left_edge_needs_no_extension(self):
        _, ref, engine, _, rows = self.run_case("A"*300, [(100,"A","C")])
        self.assertEqual(rows[0]["canonicalKey"], "GRCh38|1|100|A|C")
        self.assertEqual(rows[0]["leftmostProof"], "SNV_POSITION_INVARIANT")
        self.assertEqual((ref.reads,engine.calls), (1,1))

    def test_wrong_ref_and_unsupported_alleles_never_repaired(self):
        _, ref, engine, _, rows = self.run_case("A"*300, [(120,"C","T"),(121,"A","C,G"),(122,"AA","CT"),(123,"A","<DEL>")])
        self.assertEqual(rows[0]["reasonCode"], "NATIVE_REF_MISMATCH")
        self.assertTrue(all(r["canonicalAllele"] is None for r in rows))
        self.assertEqual((ref.reads,engine.calls), (1,0))

    def test_mononucleotide_aliases_extend_to_natural_boundary_and_keep_multigene(self):
        _, ref, engine, candidates, rows = self.run_case("C"+"A"*299, [(150,"A","AA"),(180,"A","AA")])
        self.assertEqual([r["canonicalKey"] for r in rows], ["GRCh38|1|1|C|CA"]*2)
        records, attempts, groups = N.output_records(candidates, rows)
        self.assertEqual(len(groups),1)
        self.assertEqual(groups[0]["sourceRecordIds"], ["R1","R2"])
        self.assertEqual(groups[0]["geneMembershipStatuses"], ["MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED"])
        self.assertEqual((ref.reads,engine.calls,len(attempts)),(2,2,2))
        self.assertEqual(attempts[0]["absoluteToLocalOffset"],99)
        self.assertTrue(all(r["canonicalAllele"]["position"] == 1 for r in records))

    def test_dinucleotide_rotation_matches_whole_synthetic_oracle(self):
        sequence = "G"*30 + "AT"*150 + "C"*20
        _, _, _, _, rows = self.run_case(sequence, [(151,"A","ATA"),(152,"TAT","T")])
        whole = self.root / "whole-oracle"
        whole.mkdir()
        engine = N.BcftoolsWindowEngine(self.binary,self.binary_sha,"1.23.1","1.23.1",whole)
        oracle = engine.normalize([dict(id="W1",sequence=sequence,records=[dict(id="R1",localPosition=151,ref="A",alt="ATA"),
                    dict(id="R2",localPosition=152,ref="TAT",alt="T")])],0)
        for i, row in enumerate(rows,1):
            expected = oracle[f"R{i}"]
            self.assertEqual(row["canonicalKey"], N.canonical_key("1",expected["localPosition"],expected["ref"],expected["alt"]))

    def test_final_artificial_edge_stays_incomplete_after_three_attempts(self):
        _, ref, engine, _, rows = self.run_case("A"*22000, [(21050,"A","AA")],21000,21100)
        self.assertEqual(rows[0]["status"],"INCOMPLETE")
        self.assertIsNone(rows[0]["canonicalAllele"])
        self.assertEqual((ref.reads,engine.calls), (3,3))
        self.assertEqual(rows[0]["reasonCode"],"GLOBAL_LEFTMOST_NOT_PROVEN_WITHIN_AUTHORIZED_WINDOW")

    def test_true_chromosome_position_one_is_valid(self):
        _, ref, _, _, rows = self.run_case("A"*200,[(1,"A","AA")],1,100)
        self.assertEqual(rows[0]["canonicalKey"],"GRCh38|1|1|A|AA")
        self.assertEqual(rows[0]["leftmostProof"],"NATURAL_CHROMOSOME_START")
        self.assertEqual(ref.reads,1)

    def test_right_ref_completion_49_and_native_end_refusal(self):
        sequence = "C"*199+"A"*50+"G"*51
        _, ref, _, _, rows = self.run_case(sequence,[(200,"A"*50,"A")])
        self.assertEqual(rows[0]["referenceStatus"],"MATCH")
        self.assertEqual(ref.observations[0]["rightRefCompletionBases"],49)

    def test_ambiguous_ref_or_shift_context_has_no_canonical(self):
        _, _, _, _, rows = self.run_case("G"*100+"N"+"A"*199,[(101,"A","C"),(150,"A","AA")])
        self.assertEqual(rows[0]["reasonCode"],"AMBIGUOUS_NATIVE_REFERENCE")
        self.assertEqual(rows[1]["reasonCode"],"AMBIGUOUS_NORMALIZATION_CONTEXT")
        self.assertTrue(all(r["canonicalKey"] is None for r in rows))

    def test_budget_gap_does_not_use_last_partial_canonical(self):
        _, ref, engine, _, rows = self.run_case("A"*22000,[(21050,"A","AA")],21000,21100,dict(maximumWindowReads=1))
        self.assertEqual(rows[0]["reasonCode"],"REFERENCE_QUERY_BUDGET_EXHAUSTED")
        self.assertIsNone(rows[0]["canonicalKey"])
        self.assertEqual((ref.reads,engine.calls),(1,1))

    def test_output_cap_applies_to_cli_temporary_artifacts(self):
        fixture = Fixture(self.root,"A"*300)
        directory = self.root/"output"
        directory.mkdir()
        store = N.Artifacts(directory,150,50)
        engine = N.BcftoolsWindowEngine(self.binary,self.binary_sha,"1.23.1","1.23.1",directory,store)
        with self.assertRaisesRegex(ValueError,"OUTPUT_ARTIFACT_BUDGET_EXHAUSTED"):
            N.normalize_candidates(fixture.candidates([(150,"A","AA")]),fixture.windows,fixture.reference(),engine,fixture.policy)
        self.assertLessEqual(sum(p.stat().st_size for p in store.paths),100)
        self.assertEqual(engine.calls,0)

    def test_complete_masked_to_native_output_and_alias_provenance(self):
        fixture = Fixture(self.root,"A"*300)
        _,_,_,options = source_inputs(fixture,self.binary)
        report = N.execute(options)
        self.assertEqual(report["status"],"COMPLETE",report)
        self.assertTrue(report["allSourceRecordsAccountedFor"])
        self.assertEqual(report["sourceRecords"],2)
        self.assertEqual((report["windowReads"],report["normalizerCalls"]),(1,1))
        self.assertEqual((report["finalPanelSelectedAlleles"],report["ruleEvaluations"]),(0,0))
        self.assertFalse(report["samplingAllowed"])
        self.assertFalse(report["referenceIdentity"]["fullReferenceHashComputed"])
        rows=[json.loads(line) for line in (options.output/"source-normalization.jsonl").read_text().splitlines()]
        self.assertEqual(rows[0]["canonicalKey"],"GRCh38|1|150|A|C")
        self.assertEqual(rows[0]["geneMembershipStatuses"],["MULTIPLE_GENE_IDENTITY_REVIEW_REQUIRED"])
        for artifact in report["artifactIdentities"]:
            content=(options.output/artifact["file"]).read_bytes()
            self.assertEqual((len(content),N.sha(content)),(artifact["bytes"],artifact["sha256"]))
        with self.assertRaisesRegex(ValueError,"NEW_TASK_PRIVATE_OUTPUT_DIRECTORY_REQUIRED"):
            N.execute(options)

    def test_preflight_input_failure_retains_failed_manifest_without_reference(self):
        fixture = Fixture(self.root,"A"*300)
        _,_,_,options = source_inputs(fixture,self.binary)
        options.projection.write_bytes(options.projection.read_bytes()+b" ")
        report=N.execute(options)
        self.assertEqual(report["status"],"FAILED")
        self.assertFalse(report["allSourceRecordsAccountedFor"])
        self.assertEqual((report["windowReads"],report["normalizerCalls"]),(0,0))
        self.assertIsNone(report["referenceIdentity"])

    def test_execute_cancelled_during_process_start_is_never_complete(self):
        fixture=Fixture(self.root,"A"*300)
        _,_,_,options=source_inputs(fixture,self.binary)
        lines,start=inspect.getsourcelines(N.Artifacts.process)
        target=start+next(i for i,line in enumerate(lines) if "def drain(" in line)
        captured=[]
        def cancel(frame,event,_):
            if event=="line" and frame.f_code is N.Artifacts.process.__code__ and frame.f_lineno==target:
                captured.append(frame.f_locals["process"])
                raise KeyboardInterrupt("SYNTHETIC_EXECUTE_CANCEL")
            return cancel
        try:
            sys.settrace(cancel)
            report=N.execute(options)
        finally:
            sys.settrace(None)
        self.assertEqual(report["status"],"CANCELLED")
        self.assertFalse(report["allSourceRecordsAccountedFor"])
        self.assertFalse(report["samplingAllowed"])
        self.assertIsNotNone(captured[0].poll())
        self.assertEqual(report["finalPanelSelectedAlleles"],0)

    def test_source_query_contig_mismatch_makes_entire_run_incomplete(self):
        fixture=Fixture(self.root,"A"*300)
        plan,_,manifest,options=source_inputs(fixture,self.binary)
        rows=[json.loads(line) for line in options.projection.read_text().splitlines()]
        rows[0]["CHROM"]="2"
        content=b"".join(N.canonical(row)+b"\n" for row in rows)
        options.projection.write_bytes(content)
        manifest["artifacts"][0].update(bytes=len(content),sha256=N.sha(content))
        plan["bindings"]["maskedProjectionSha256"]=N.sha(content)
        plan["bindings"]["poolManifestSha256"]=dump(options.pool_manifest,manifest)
        options.plan_sha256=dump(options.plan,plan)
        report=N.execute(options)
        self.assertEqual(report["status"],"INCOMPLETE")
        self.assertEqual(report["unresolvedRecords"],1)
        results=[json.loads(line) for line in (options.output/"source-normalization.jsonl").read_text().splitlines()]
        self.assertEqual(results[0]["reasonCode"],"SOURCE_CONTIG_QUERY_MISMATCH")
        self.assertIsNone(results[0]["canonicalAllele"])
        self.assertFalse(report["samplingAllowed"])

    def test_original_ref_left_of_tabix_window_and_natural_right_end(self):
        sequence="C"*98+"AA"+"G"*200
        _,ref,_,_,rows=self.run_case(sequence,[(99,"AA","A")])
        self.assertEqual(rows[0]["canonicalKey"],"GRCh38|1|98|CA|C")
        self.assertEqual(ref.reads,2)

    def test_original_ref_cannot_run_beyond_real_chromosome_end(self):
        _,ref,engine,_,rows=self.run_case("A"*230,[(200,"A"*50,"A")])
        self.assertEqual(rows[0]["reasonCode"],"REFERENCE_ALLELE_EXCEEDS_NATIVE_CONTIG")
        self.assertIsNone(rows[0]["canonicalAllele"])
        self.assertEqual((ref.reads,engine.calls),(1,0))


if __name__ == "__main__":
    unittest.main()
