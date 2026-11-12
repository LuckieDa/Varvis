"""Frozen synthetic controls; fake responses are never claimed as Gemini runs."""
import base64
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from prepare_literature import prepare
from extract_literature_cases import extract
from model_literature_contract import canonical, digest, model_request, replay_candidates, equivalent_field, check_shape
from model_transport import (config, credentials, public_config, decode_response, invoke, units_cost,
                             exclusive, atomic, bounded_transport)
from model_replay import binding, replay_artifact
from test_literature_source_assertions import ARTICLE, RECORD, PMCID, VCV, sha
from qualify_literature_cases import shared_field_matches
from resume_model_pilot import resume


def settings():
    c = json.loads((Path(__file__).parent / "gemini-pilot.example.json").read_bytes())
    c.update(enabled=True, pricingConfirmed=True, endpoint="https://example.invalid/v1/chat/completions",
             expectedResponseModel="gemini-3-flash-preview")
    return c


def proposed(raw=ARTICLE):
    d = prepare(raw, PMCID, sha(raw)); rows = extract(d)["cases"]
    return {"cases": [{"caseLabel": r["caseLabel"], "fields": [
        {"field": f["field"], "sourceValue": f["sourceValue"], "citation": {
            "unitId": f["citation"]["unitId"], "quote": f["citation"]["quote"]}}
        for f in r["fields"] if f["findingState"] == "REPORTED"]} for r in rows]}


def response(candidates=None):
    return canonical({"model": "gemini-3-flash-preview", "choices": [{"finish_reason": "stop", "message": {
        "role": "assistant", "content": canonical(proposed() if candidates is None else candidates).decode()}}],
        "usage": {"prompt_tokens": 1000, "completion_tokens": 100, "total_tokens": 1100,
                  "completion_tokens_details": {"reasoning_tokens": 40}}})


def facts(raw=ARTICLE, candidates=None):
    return replay_candidates(raw, PMCID, sha(raw), proposed(raw) if candidates is None else candidates,
                             [(RECORD, sha(RECORD), VCV)])


def sleeping_worker(*unused):
    import time
    time.sleep(30)  # local child only, no socket or provider


def write_synthetic_fixture(root):
    """Java integration fixture, guarded against accidental real-paper use."""
    root = Path(root); raw = (root / "synthetic-literature.xml").read_bytes()
    if b"SYNTHETIC only" not in raw:
        raise ValueError("SYNTHETIC_FIXTURE_REQUIRED")
    d = prepare(raw, PMCID, sha(raw)); p = proposed(raw)
    if not p["cases"]:
        u = next(u for u in d["units"] if u["kind"] == "BODY_PARAGRAPH")
        p = {"cases": [{"caseLabel": "A", "fields": [
            {"field": f, "sourceValue": v, "citation": {"unitId": u["unitId"], "quote": u["text"]}}
            for f, v in [("geneSymbol", "EXAMPLE"), ("variantText", "c.123A>G"), ("proteinChange", "p.T41A"),
                         ("reportedPhenotype", "Synthetic trait")]]}]}
    c = settings(); request = model_request(d, c["model"], c["maxOutputTokens"])
    result, receipt, unused = invoke(c, "synthetic-test-key", request, root / "fake-model-state", binding(d),
                                      lambda *unused: response(p))
    artifact = {"schemaVersion": "denovo-model-artifact-v1", "receipt": receipt,
                "responseBase64": base64.b64encode(result).decode()}
    artifact_path = root / "fake-model-artifact.json"; artifact_path.write_bytes(canonical(artifact))
    index = {"schemaVersion": "denovo-model-artifact-index-v1", "artifacts": [
        {"sourceSha256": sha(raw), "pmcid": PMCID, "path": artifact_path.name, "sha256": sha(artifact_path.read_bytes())}]}
    (root / "model-artifacts.json").write_bytes(canonical(index))


class CandidateTest(unittest.TestCase):
    def test_request_schema_and_validator_accept_documented_case_prefix(self):
        p = proposed(); p["cases"][0]["caseLabel"] = "Case A"
        check_shape(p)
        c = facts(candidates=p)["cases"][0]
        self.assertEqual("A", c["caseLabel"])
        self.assertEqual([], c["issues"])
        self.assertEqual("PUBLISHED_BOTH_PARENTS_CONFIRMED", c["parentage"]["status"])
        p["cases"].append(deepcopy(p["cases"][0])); p["cases"][1]["caseLabel"] = "A"
        self.assertIn("LITERATURE_CASE_COHORT_AMBIGUOUS", facts(candidates=p)["cases"][0]["issues"])

    def test_only_hgvs_typographic_aliases_are_equivalent(self):
        self.assertTrue(equivalent_field("variantText", "123A>G", "c.123A>G"))
        self.assertTrue(equivalent_field("proteinChange", "T41A", "p. Thr41Ala"))
        for field, a, b in [("variantText", "123A>G", "124A>G"),
                            ("variantText", "NM_123.1:c.123A>G", "NM_123.2:c.123A>G"),
                            ("variantText", "NM_123.1:c.123A>G", "c.123A>G"),
                            ("proteinChange", "T41A", "p.T41V"),
                            ("reportedParentage", "confirmed", "not confirmed")]:
            with self.subTest(field=field, b=b): self.assertFalse(equivalent_field(field, a, b))

    def test_prose_hgvs_with_prefix_is_not_a_false_native_conflict(self):
        raw = ARTICLE.replace(b"p.T41A", b"p. T41A")
        d = prepare(raw, PMCID, sha(raw)); p = proposed(raw)
        u = next(u for u in d["units"] if u["kind"] == "BODY_PARAGRAPH")
        for f in p["cases"][0]["fields"]:
            if f["field"] in {"variantText", "proteinChange"}:
                f["sourceValue"] = {"variantText": "c.123A>G", "proteinChange": "p. T41A"}[f["field"]]
                f["citation"] = {"unitId": u["unitId"], "quote": u["text"]}
        c = facts(raw, p)["cases"][0]
        self.assertEqual([], c["issues"])
        self.assertEqual("ALIAS_RESOLVED_REFERENCE_CHECK_PENDING", c["alleleIdentity"]["status"])

    def test_explicit_shared_case_range_is_scoped_and_context_checked(self):
        raw = ARTICLE.replace(b"Case A", b"Case 1").replace(b"SNP analysis confirmed both maternity and paternity.", b"")
        def with_shared(sentence):
            return raw.replace(b"<sec><title>Case 1:", b"<p>" + sentence + b"</p><sec><title>Case 1:")
        good = b"The mutations identified in Cases 1-3 were de novo, with parentage confirmed using SNP analysis."
        c = facts(with_shared(good))["cases"][0]
        self.assertEqual("PUBLISHED_BOTH_PARENTS_CONFIRMED", c["parentage"]["status"])
        self.assertEqual("SUPPORTED_EXPLICIT_FAMILY_STATEMENTS", c["contextVerification"])
        for sentence in [good.replace(b"1-3", b"2-3"), good.replace(b"were de novo", b"were not de novo"),
                         good.replace(b"Cases 1-3", b"all cases")]:
            self.assertEqual("UNKNOWN", facts(with_shared(sentence))["cases"][0]["parentage"]["status"])
        negative = facts(with_shared(good + b" Cases 1-3 had inconclusive parentage."))["cases"][0]
        self.assertEqual("UNRESOLVED", negative["contextVerification"])
        self.assertTrue(negative["unresolvedContext"])

    def test_prompt_requests_missing_case_rows_without_inventing_variants(self):
        r = model_request(prepare(ARTICLE, PMCID, sha(ARTICLE)), "gemini-3-flash", 8192)
        self.assertEqual(["A"], json.loads(r["messages"][1]["content"])["sourceCaseLabels"])
        self.assertEqual("minimal", r["reasoning_effort"])
        self.assertIn("never invent an allele", r["messages"][0]["content"])

    def test_group_diagnosis_is_bound_to_affirmative_clause(self):
        sentence = b"Cases 1-3 were diagnosed with Example syndrome based on criteria, with no evidence of regression."
        raw = ARTICLE.replace(b"<sec><title>Case A:", b"<p>" + sentence + b"</p><sec><title>Case A:")
        d = prepare(raw, PMCID, sha(raw)); u = next(u for u in d["units"] if u["text"] == sentence.decode())
        f = {"field": "reportedDiagnosis", "sourceValue": "Example syndrome", "citation": {"unitId": u["unitId"], "quote": "Example syndrome"}}
        self.assertTrue(shared_field_matches(d, "1", f))
        self.assertFalse(shared_field_matches(d, "4", f))
        f["sourceValue"] = "regression"; f["citation"]["quote"] = "regression"
        self.assertFalse(shared_field_matches(d, "1", f))

    def test_explicit_native_facts_replayed_and_no_model_scores(self):
        c = facts()["cases"][0]
        self.assertEqual([], c["issues"])
        self.assertEqual("PUBLISHED_BOTH_PARENTS_CONFIRMED", c["parentage"]["status"])
        self.assertEqual("PUBLISHED_TRIO_SANGER_DENOVO", c["targetTesting"]["status"])
        self.assertEqual("SUPPORTED_EXPLICIT_FAMILY_STATEMENTS", c["contextVerification"])
        self.assertEqual("NONE", facts()["clinicalAdoption"])

    def test_parentage_not_tested_and_inherited_are_not_confirmation(self):
        for replacement, expected in [(b"No parentage testing was performed.", "PUBLISHED_PARENTAGE_NOT_TESTED"),
                                      (b"Parentage was not reported.", "PUBLISHED_PARENTAGE_NOT_REPORTED"),
                                      (b"SNP analysis did not confirm maternity or paternity.", "UNKNOWN")]:
            with self.subTest(replacement=replacement):
                c = facts(ARTICLE.replace(b"SNP analysis confirmed both maternity and paternity.", replacement))["cases"][0]
                self.assertEqual(expected, c["parentage"]["status"])

    def test_uncited_negative_context_is_not_omitted(self):
        raw = ARTICLE.replace(b"</p></sec>", b"The variant was inherited from the mother.</p></sec>")
        c = facts(raw)["cases"][0]
        self.assertEqual("UNRESOLVED", c["contextVerification"])
        self.assertIn("LITERATURE_SPECIAL_OR_CONFLICT_TEXT_NOT_AUTOMATICALLY_RESOLVED", c["issues"])

    def test_exact_quote_with_wrong_value_or_nonexistent_quote_rejected(self):
        for mutate in (lambda f: f.update(sourceValue="invented"), lambda f: f["citation"].update(quote="invented")):
            p = proposed(); mutate(p["cases"][0]["fields"][0])
            self.assertTrue(any("MODEL_CITATION" in x for x in facts(candidates=p)["cases"][0]["issues"]))

    def test_model_cannot_supply_approval_coordinates_points_or_status(self):
        for key in ("points", "clinicalAdoption", "parentage", "alleleIdentity", "contextVerification"):
            p = proposed(); p["cases"][0][key] = "APPROVED"
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "SCHEMA_INVALID"):
                facts(candidates=p)

    def test_case_label_change_and_other_cases_table_cells_do_not_qualify(self):
        p = proposed(); p["cases"][0]["caseLabel"] = "B"
        self.assertTrue(any("ATTRIBUTION" in x for x in facts(candidates=p)["cases"][0]["issues"]))

    def test_missing_case_cannot_falsely_establish_single_occurrence(self):
        raw = ARTICLE.replace(b"</body>", b"<sec><title>Results</title><sec><title>Case B</title><p>Another case.</p></sec></sec></body>")
        c = facts(raw)["cases"][0]
        self.assertIn("LITERATURE_FIELD_CONFLICT:MODEL_CASE_COVERAGE_INCOMPLETE", c["issues"])

    def test_repeated_case_and_conflicting_field_rejected(self):
        p = proposed(); p["cases"].append(deepcopy(p["cases"][0]))
        self.assertIn("LITERATURE_CASE_COHORT_AMBIGUOUS", facts(candidates=p)["cases"][0]["issues"])

    def test_prompt_contains_complete_source_context_not_expected_scores(self):
        r = model_request(prepare(ARTICLE, PMCID, sha(ARTICLE)), "gemini-3-flash-preview", 8192)
        self.assertIn("SNP analysis", r["messages"][1]["content"])
        self.assertIn("table", r["messages"][1]["content"])
        self.assertNotIn("expectedPoints", json.dumps(r))
        self.assertNotIn("tools", r)

    def test_narrative_fields_without_table_can_reuse_existing_assertion_rules(self):
        # New model role: field discovery in prose, not a new per-gene rule or
        # semantic authority. This is an explicitly SYNTHETIC provider response.
        import re
        raw = re.sub(b"<table-wrap>.*?</table-wrap>", b"", ARTICLE, flags=re.S)
        raw = raw.replace(b"</p></sec>", b" The proband had Synthetic trait. There was no family history of similar symptoms.</p></sec>")
        d = prepare(raw, PMCID, sha(raw)); u = next(u for u in d["units"] if u["kind"] == "BODY_PARAGRAPH")
        p = {"cases": [{"caseLabel": "A", "fields": [
            {"field": field, "sourceValue": value, "citation": {"unitId": u["unitId"], "quote": u["text"]}}
            for field, value in [("geneSymbol", "EXAMPLE"), ("variantText", "c.123A>G"), ("proteinChange", "p.T41A"),
                                 ("reportedPhenotype", "Synthetic trait")]]}]}
        c = facts(raw, p)["cases"][0]
        self.assertEqual([], c["issues"])
        self.assertEqual("REPORTED_NONE", c["familyHistory"])
        self.assertEqual("SUPPORTED_EXPLICIT_FAMILY_STATEMENTS", c["contextVerification"])
        for before, after in [(b"The proband had", b"The mother had"), (b"had Synthetic", b"had no Synthetic")]:
            bad = raw.replace(before, after)
            changed = deepcopy(p)
            changed["cases"][0]["fields"][-1]["citation"]["quote"] = u["text"].replace(before.decode(), after.decode())
            result = facts(bad, changed)["cases"][0]
            self.assertIn("LITERATURE_FIELD_CONFLICT:MODEL_PHENOTYPE_SUBJECT_UNRESOLVED", result["issues"])

    def test_same_real_quote_cannot_be_relabelled_as_another_field(self):
        p = proposed(); p["cases"][0]["fields"][0]["field"] = "reportedPhenotype"
        self.assertTrue(any("ATTRIBUTION" in x for x in facts(candidates=p)["cases"][0]["issues"]))


class TransportTest(unittest.TestCase):
    def test_explicit_billed_cap_includes_thinking_without_double_counting(self):
        c = {**self.c, "maxOutputTokens": 256, "maxBilledOutputTokens": 1024}
        value = json.loads(response()); value["usage"].update(completion_tokens=700, total_tokens=1700,
            completion_tokens_details={"reasoning_tokens": 500})
        unused, usage = decode_response(canonical(value), c)
        self.assertEqual(5200, units_cost(c, usage["prompt_tokens"], usage["completion_tokens"]))
        value["usage"]["completion_tokens_details"]["reasoning_tokens"] = 0
        with self.assertRaisesRegex(ValueError, "VISIBLE_OUTPUT_LIMIT"):
            decode_response(canonical(value), c)
        value["usage"].update(completion_tokens=1025, total_tokens=2025)
        with self.assertRaisesRegex(ValueError, "OUTPUT_BILLING_LIMIT"):
            decode_response(canonical(value), c)

    def test_billed_cap_is_reserved_and_budget_cannot_be_bypassed(self):
        self.c.update(maxBilledOutputTokens=16384, budgetRmb='0.02')
        with self.assertRaisesRegex(ValueError, "BUDGET_EXHAUSTED"):
            self.invoke()
        self.assertEqual(0, self.count)

    def test_reviewed_billed_cap_transition_preserves_money_and_original_ledger(self):
        pin, preflight, review = self.continuation_fixture()
        old = json.loads((self.root / "budget.json").read_bytes())
        record = json.loads(review.read_bytes()); record.update(previousConfiguration=public_config(self.c), billedOutputChangeReviewed=True)
        review.write_bytes(canonical(record))
        changed = {**self.c, 'maxBilledOutputTokens': 16384}
        resume(changed, self.root, pin, preflight, review)
        state = json.loads((self.root / 'budget.json').read_bytes())
        self.assertEqual(old['calls'], state['calls'])
        self.assertEqual(digest(canonical(public_config(changed))), state['configurationSha256'])
        self.assertEqual(1, len(state['resumeHistory']))

    def continuation_fixture(self):
        bad = response({"cases": [{"caseLabel": "invalid label with spaces", "fields": []}]})
        with self.assertRaisesRegex(ValueError, "CASE_SCHEMA_INVALID"):
            self.invoke(lambda *unused: bad)
        self.request["messages"][0]["content"] += " Reviewed format correction."
        preflight = self.root / "preflight.json"
        preflight.write_bytes(canonical({"schemaVersion": "denovo-model-preflight-v1", "executeRequested": False,
            "requests": [{"requestSha256": digest(canonical(self.request))}]}))
        review = self.root / "review.json"
        review.write_bytes(canonical({"schemaVersion": "denovo-pilot-continuation-review-v1",
            "authorization": "USER_GOAL_AUTHORIZED_DEVELOPMENT", "diagnosis": "Synthetic explicit schema mismatch reviewed offline.",
            "offlineChecksPassed": True, "responseSha256": digest(bad), "nextRequestSha256": digest(canonical(self.request))}))
        return digest((self.root / "budget.json").read_bytes()), preflight, review

    def test_reviewed_continuation_preserves_failed_call_and_reserved_cost(self):
        pin, preflight, review = self.continuation_fixture()
        old = json.loads((self.root / "budget.json").read_bytes())
        result = resume(self.c, self.root, pin, preflight, review)
        self.assertEqual(1, result["priorCallsRetained"])
        self.invoke()
        state = json.loads((self.root / "budget.json").read_bytes())
        self.assertEqual(old["calls"][0], state["calls"][0])
        self.assertEqual(2, len(state["calls"]))
        self.assertEqual("FAILED_RESERVED", state["calls"][0]["status"])
        self.assertEqual(100, state["calls"][0]["reportedUsage"]["completion_tokens"])
        self.assertTrue(state["calls"][1]["continuationReviewSha256"])

    def test_visible_limit_continuation_keeps_reserve_and_round_limit(self):
        unused, preflight, review = self.continuation_fixture()
        path = self.root / 'budget.json'
        state = json.loads(path.read_bytes())
        state['stoppedReason']='VISIBLE_OUTPUT_LIMIT_EXCEEDED'
        state['calls'][-1]['reasonCode']='DENOVO_MODEL_VISIBLE_OUTPUT_LIMIT_EXCEEDED'
        path.write_bytes(canonical(state)); before=state['calls'][:]
        result=resume(self.c,self.root,digest(path.read_bytes()),preflight,review)
        self.assertEqual('ONE_CHANGED_REQUEST_PERMITTED',result['status'])
        self.assertEqual(before,json.loads(path.read_bytes())['calls'])

    def test_continuation_requires_pinned_review_and_changed_request(self):
        pin, preflight, review = self.continuation_fixture()
        with self.assertRaisesRegex(ValueError, "BUDGET_CHANGED"):
            resume(self.c, self.root, "0" * 64, preflight, review)
        r = json.loads(review.read_bytes()); r["responseSha256"] = "0" * 64; review.write_bytes(canonical(r))
        with self.assertRaisesRegex(ValueError, "REVIEW_RESPONSE_PIN"):
            resume(self.c, self.root, pin, preflight, review)

    def test_profile_schema_failure_resume_retains_original_reservation(self):
        unused, preflight, review = self.continuation_fixture()
        path = self.root / 'budget.json'; state = json.loads(path.read_bytes())
        state['stoppedReason'] = 'SCHEMA_INVALID_RESPONSE_PROFILE_MISMATCH'
        state['calls'][-1]['reasonCode'] = 'DENOVO_MODEL_' + state['stoppedReason']
        path.write_bytes(canonical(state)); before = state['calls'][:]
        result = resume(self.c, self.root, digest(path.read_bytes()), preflight, review)
        self.assertEqual('ONE_CHANGED_REQUEST_PERMITTED', result['status'])
        self.assertEqual(before, json.loads(path.read_bytes())['calls'])

    def test_continuation_permit_cannot_run_a_different_request_or_reset_configuration(self):
        pin, preflight, review = self.continuation_fixture()
        with self.assertRaisesRegex(ValueError, "BUDGET_CONFIGURATION_CHANGED"):
            resume({**self.c, "budgetRmb": "19"}, self.root, pin, preflight, review)
        resume(self.c, self.root, pin, preflight, review)
        self.request["messages"][0]["content"] += " Unreviewed extra change."
        with self.assertRaisesRegex(ValueError, "REVIEWED_CONTINUATION_REQUEST"):
            self.invoke()
        self.assertEqual(1, len(json.loads((self.root / "budget.json").read_bytes())["calls"]))

    def test_continuation_adjustment_limit_cannot_be_reset(self):
        pin, preflight, review = self.continuation_fixture()
        state = json.loads((self.root / "budget.json").read_bytes()); state["resumeHistory"] = [{}, {}, {}]
        (self.root / "budget.json").write_bytes(canonical(state))
        pin = digest((self.root / "budget.json").read_bytes())
        with self.assertRaisesRegex(ValueError, "ADJUSTMENT_ROUND_LIMIT"):
            resume(self.c, self.root, pin, preflight, review)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.c = settings()
        self.document = prepare(ARTICLE, PMCID, sha(ARTICLE))
        self.request = model_request(self.document, self.c["model"], self.c["maxOutputTokens"])
        self.count = 0

    def fake(self, c, key, raw):
        self.count += 1
        return response()

    def invoke(self, transport=None, **kwargs):
        return invoke(self.c, "secret-not-printed", self.request, self.root, binding(self.document), transport or self.fake, **kwargs)

    def test_config_requires_mapping_price_and_https(self):
        for field, value in [("enabled", False), ("pricingConfirmed", False), ("endpoint", "http://example.invalid/v1/chat/completions"),
                             ("expectedResponseModel", ""), ("budgetRmb", "21"), ("maxCalls", 101),
                             ("endpoint", "https://key@example.invalid/v1/chat/completions")]:
            c = {**self.c, field: value}; p = self.root / "config.json"; p.write_bytes(canonical(c))
            with self.subTest(field=field), self.assertRaises(ValueError): config(p)

    def test_total_deadline_terminates_and_joins_owned_process(self):
        import multiprocessing
        import time
        before = {p.pid for p in multiprocessing.active_children()}
        c = {**self.c, "timeoutSeconds": 1}
        start = time.monotonic()
        with self.assertRaisesRegex(ValueError, "TIMEOUT"):
            bounded_transport(c, "synthetic-test-key", b"{}", sleeping_worker)
        self.assertLess(time.monotonic() - start, 8)
        self.assertEqual(before, {p.pid for p in multiprocessing.active_children()})

    def test_same_private_key_file_only(self):
        c = {**self.c, "apiKeyEnvironment": "VARVIS_TEST_NO_KEY", "apiKeyFile": "api-key.txt"}
        (self.root / "api-key.txt").write_text("synthetic-token")
        self.assertEqual("synthetic-token", credentials(c, self.root / "config.json"))
        c["apiKeyFile"] = "../elsewhere.txt"
        with self.assertRaisesRegex(ValueError, "KEY_PATH"): credentials(c, self.root / "config.json")

    def test_single_call_cache_reopen_has_no_new_transmission(self):
        one = self.invoke(); two = self.invoke()
        self.assertFalse(one[2]); self.assertTrue(two[2]); self.assertEqual(1, self.count)
        self.assertEqual(one[0], two[0])
        self.assertEqual("SYNTHETIC_TEST", one[1]["transportKind"])
        budget = json.loads((self.root / "budget.json").read_bytes())
        self.assertEqual(1600, budget["calls"][0]["chargedOrReservedMicroCredits"])

    def test_thinking_not_charged_twice(self):
        unused, usage = decode_response(response(), self.c)
        self.assertEqual(1600, units_cost(self.c, usage["prompt_tokens"], usage["completion_tokens"]))

    def test_failures_reserve_cost_and_stop_later_requests(self):
        def fail(*unused):
            self.count += 1
            raise ValueError("DENOVO_MODEL_RATE_LIMITED")
        with self.assertRaisesRegex(ValueError, "RATE_LIMITED"): self.invoke(fail)
        with self.assertRaises((ValueError, FileNotFoundError)): self.invoke(fail)
        self.assertEqual(1, self.count)
        state = json.loads((self.root / "budget.json").read_bytes())
        self.assertGreater(state["calls"][0]["chargedOrReservedMicroCredits"], 0)
        self.assertEqual("RATE_LIMITED", state["stoppedReason"])

    def test_budget_call_cap_and_input_cap_reject_before_network(self):
        self.c["budgetRmb"] = "0.000001"
        with self.assertRaisesRegex(ValueError, "BUDGET_EXHAUSTED"): self.invoke()
        self.assertEqual(0, self.count)

    def test_oversize_input_rejected_without_truncation(self):
        self.c["maxRequestBytes"] = 100
        with self.assertRaisesRegex(ValueError, "INPUT_TOO_LARGE"): self.invoke()
        self.assertEqual(0, self.count)

    def test_malformed_negative_budget_accounting_cannot_free_allowance(self):
        atomic(self.root / "budget.json", {"configurationSha256": digest(canonical(public_config(self.c))),
             "calls": [{"status": "SUCCESS", "chargedOrReservedMicroCredits": -10000}], "stoppedReason": None})
        with self.assertRaisesRegex(ValueError, "BUDGET_STATE_INVALID"): self.invoke()
        self.assertEqual(0, self.count)

    def test_changed_prompt_requires_new_call_and_retains_prior_charge(self):
        self.invoke(); self.request["messages"][0]["content"] += " Explicit new prompt revision."
        self.invoke(); self.assertEqual(2, self.count)
        self.assertEqual(2, len(json.loads((self.root / "budget.json").read_bytes())["calls"]))

    def test_call_limit_applies_across_different_sources(self):
        self.c["maxCalls"] = 1
        self.invoke()
        self.request["messages"][1]["content"] += " "
        with self.assertRaisesRegex(ValueError, "CALL_LIMIT"): self.invoke()
        self.assertEqual(1, self.count)

    def test_concurrent_process_cannot_bypass_budget(self):
        with exclusive(self.root):
            with self.assertRaisesRegex(ValueError, "BUSY_OR_INTERRUPTED"): self.invoke()
        self.assertEqual(0, self.count)

    def test_interrupted_reservation_stops_without_retry(self):
        atomic(self.root / "budget.json", {"configurationSha256": digest(canonical(public_config(self.c))),
             "calls": [{"status": "RESERVED", "chargedOrReservedMicroCredits": 1}], "stoppedReason": None})
        with self.assertRaisesRegex(ValueError, "UNSETTLED_CALL"): self.invoke()
        self.assertEqual(0, self.count)

    def test_corrupted_cache_cannot_be_reused_or_refetched(self):
        self.invoke()
        p = next((self.root / "responses").glob("*/response.json")); p.write_bytes(b"{}")
        with self.assertRaisesRegex(ValueError, "CACHE_PIN_MISMATCH"): self.invoke()
        self.assertEqual(1, self.count)

    def test_model_mapping_changed_budget_does_not_reset(self):
        self.invoke(); self.c["model"] += "-changed"
        self.request["model"] = self.c["model"]
        with self.assertRaisesRegex(ValueError, "BUDGET_CONFIGURATION_CHANGED"): self.invoke()
        self.assertEqual(1, self.count)

    def test_credentials_not_in_artifacts_and_echo_not_saved(self):
        def echo(*unused): return b'secret-not-printed'
        with self.assertRaisesRegex(ValueError, "CREDENTIAL_ECHO"): self.invoke(echo)
        for p in self.root.rglob("*.json"):
            self.assertNotIn(b"secret-not-printed", p.read_bytes())

    def test_incomplete_output_usage_and_wrong_model_rejected(self):
        for mutation in (lambda r: r["choices"][0].update(finish_reason="length"),
                         lambda r: r.pop("usage"), lambda r: r.update(model="other"),
                         lambda r: r["usage"].update(total_tokens=999),
                         lambda r: r["usage"]["completion_tokens_details"].update(reasoning_tokens=101)):
            r = json.loads(response()); mutation(r)
            with self.assertRaises(ValueError): decode_response(canonical(r), self.c)

    def test_duplicate_json_key_rejected(self):
        with self.assertRaisesRegex(ValueError, "DUPLICATE_JSON"):
            decode_response(b'{"model":"gemini-3-flash-preview","model":"other"}', self.c)

    def test_replay_binds_response_request_implementation_and_usage(self):
        raw, receipt, unused = self.invoke()
        artifact = {"schemaVersion": "denovo-model-artifact-v1", "receipt": receipt,
                    "responseBase64": base64.b64encode(raw).decode()}
        args = (ARTICLE, PMCID, sha(ARTICLE))
        f = replay_artifact(*args, artifact, [(RECORD, sha(RECORD), VCV)])
        self.assertEqual(0, f["modelCalls"])
        for mutate in (lambda a: a["receipt"]["identity"].update(requestSha256="0" * 64),
                       lambda a: a["receipt"]["identity"]["binding"].update(sourceSha256="0" * 64),
                       lambda a: a["receipt"].update(responseSha256="0" * 64),
                       lambda a: a["receipt"]["usage"].update(total_tokens=1)):
            bad = deepcopy(artifact); mutate(bad)
            with self.assertRaises(ValueError): replay_artifact(*args, bad)

    def test_frozen_literal_audit_keeps_synthetic_and_real_attribution_separate(self):
        from evaluate_model_pilot import evaluate
        (self.root / "synthetic-literature.xml").write_bytes(ARTICLE)
        write_synthetic_fixture(self.root)
        f = proposed()["cases"][0]["fields"][0]
        gold = {"cases": [{"pmcid": PMCID, "caseLabel": "A", "expectedFields": [
            {"field": f["field"], "sourceValue": f["sourceValue"], "unitId": f["citation"]["unitId"]}]}]}
        p = self.root / "gold.json"; p.write_bytes(canonical(gold))
        report = evaluate(p, self.root / "model-artifacts.json")
        self.assertEqual(1, report["passed"]); self.assertEqual(1, report["syntheticResponses"])
        self.assertEqual(0, report["realProviderResponses"]); self.assertEqual(0, report["modelCalls"])
        gold["cases"][0]["expectedFields"][0]["sourceValue"] = "WRONG"
        p.write_bytes(canonical(gold)); report = evaluate(p, self.root / "model-artifacts.json")
        self.assertEqual(0, report["passed"])


if __name__ == "__main__":
    unittest.main()
