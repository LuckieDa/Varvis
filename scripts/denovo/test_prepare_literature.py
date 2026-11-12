import hashlib
import unittest
from prepare_literature import prepare, verify_citation, MAX_BYTES

# Invented article and identities; never represented as a real PMC record.
SYNTHETIC = b'''<article><front><article-meta><article-id pub-id-type="pmcid">PMC999999999</article-id>
<title-group><article-title>SYNTHETIC verification fixture</article-title></title-group>
<abstract><p>Abstract with <italic>limited</italic> context.</p></abstract></article-meta></front>
<body><sec id="results"><title>Results</title><p id="p1">Mother was tested. Father was not tested.</p>
<table-wrap id="t1"><caption><p>SYNTHETIC cases</p></caption><table><thead><tr><th rowspan="2">Case</th><th colspan="2">Parents</th></tr>
<tr><th>Mother</th><th>Father</th></tr></thead><tbody><tr><td>A</td><td>Negative</td><td>Not tested</td></tr></tbody></table></table-wrap>
<p>Another source <xref ref-type="bibr" rid="r1">1</xref> reports this case.</p>
<supplementary-material xmlns:xlink="http://www.w3.org/1999/xlink" xlink:href="supplement.pdf"/></sec></body>
<back><ref-list><ref id="r1"><mixed-citation>Prior article about same family.</mixed-citation></ref></ref-list></back></article>'''


def build(raw=SYNTHETIC):
    return prepare(raw, "PMC999999999", hashlib.sha256(raw).hexdigest())


def cite(document, citation, raw=SYNTHETIC):
    return verify_citation(raw, "PMC999999999", document["sourceSha256"], citation)


class LiteraturePreparationTest(unittest.TestCase):
    def test_preserves_table_spans_without_inventing_column_associations(self):
        d = build()
        table = d["tables"][0]
        self.assertEqual(2, table["rows"][0]["cells"][0]["rowspan"])
        self.assertEqual(2, table["rows"][0]["cells"][1]["colspan"])
        self.assertEqual("Not tested", table["rows"][2]["cells"][2]["text"])
        self.assertEqual("SOURCE_SPANS_PRESERVED_NOT_FLATTENED", table["layoutStatus"])

    def test_separates_references_abstract_body_and_cells(self):
        d = build()
        self.assertEqual({"REFERENCE", "ABSTRACT", "BODY_PARAGRAPH", "CAPTION", "TABLE_CELL"}, {u["kind"] for u in d["units"]})
        self.assertEqual(1, sum(u["text"] == "SYNTHETIC cases" for u in d["units"]))
        self.assertTrue(any(link["rid"] == "r1" for link in d["links"]))
        self.assertTrue(any(link["href"] == "supplement.pdf" for link in d["links"]))
        self.assertEqual(0, d["modelCalls"])
        self.assertEqual("SOURCE_PREPARED_NOT_CLINICALLY_QUALIFIED", d["qualification"])

    def test_citation_match_is_not_fact_approval(self):
        d = build()
        u = next(u for u in d["units"] if u["xmlId"] == "p1")
        citation = {"sourceSha256": d["sourceSha256"], "unitId": u["unitId"], "start": 0, "end": 18, "quote": "Mother was tested."}
        self.assertEqual("CITATION_MATCHED_SEMANTICS_NOT_VERIFIED", cite(d, citation))
        self.assertEqual("QUOTE_MISMATCH", cite(d, {**citation, "quote": "Father was tested."}))
        self.assertEqual("SOURCE_MISMATCH", cite(d, {**citation, "sourceSha256": "0" * 64}))
        self.assertEqual("UNIT_NOT_FOUND", cite(d, {**citation, "unitId": "/unknown"}))
        self.assertEqual("SOURCE_NOT_VERIFIED", cite(d, citation, SYNTHETIC.replace(b"not tested", b"tested")))
        for bad in (True, -1, 10000, "0"):
            self.assertEqual("SPAN_INVALID", cite(d, {**citation, "start": bad}))

    def test_wrong_article_or_raw_digest_rejected(self):
        with self.assertRaisesRegex(ValueError, "SOURCE_SHA_MISMATCH"):
            prepare(SYNTHETIC, "PMC999999999", "0" * 64)
        with self.assertRaisesRegex(ValueError, "PMCID_MISMATCH"):
            prepare(SYNTHETIC, "PMC1", hashlib.sha256(SYNTHETIC).hexdigest())

    def test_external_jats_doctype_is_not_fetched_but_entities_rejected(self):
        self.assertEqual("PMC999999999", build(b'<!DOCTYPE article SYSTEM "https://invalid.example/do-not-fetch.dtd">' + SYNTHETIC)["pmcid"])
        with self.assertRaisesRegex(ValueError, "ENTITY_DECLARATION_FORBIDDEN"):
            build(b'<!DOCTYPE article [<!ENTITY x "bomb">]>' + SYNTHETIC)

    def test_empty_malformed_and_oversized_sources_rejected(self):
        for raw in (b"", b"x" * (MAX_BYTES + 1)):
            with self.assertRaisesRegex(ValueError, "SIZE_LIMIT"): build(raw)
        with self.assertRaisesRegex(ValueError, "XML_INVALID"): build(b"<article>")
        with self.assertRaisesRegex(ValueError, "JATS_ARTICLE_REQUIRED"): build(b"<html/>")

    def test_invalid_span_cannot_rearrange_cases(self):
        for replacement in (b'colspan="0"', b'colspan="1000000000"', b'colspan="x"'):
            with self.assertRaisesRegex(ValueError, "TABLE_SPAN_INVALID"):
                build(SYNTHETIC.replace(b'colspan="2"', replacement))

    def test_metadata_conflict_and_missing_body(self):
        with self.assertRaisesRegex(ValueError, "IDENTIFIER_CONFLICT"):
            build(SYNTHETIC.replace(b'</article-meta>', b'<article-id pub-id-type="pmcid">PMC1</article-id></article-meta>'))
        with self.assertRaisesRegex(ValueError, "BODY_REQUIRED"):
            build(SYNTHETIC.replace(b'<body>', b'<other>').replace(b'</body>', b'</other>'))

    def test_deterministic_units_and_exact_quote_offsets(self):
        first = build(); second = build()
        self.assertEqual(first, second)
        self.assertEqual(len(first["units"]), len({u["unitId"] for u in first["units"]}))
        for u in first["units"]:
            self.assertEqual(hashlib.sha256(u["text"].encode()).hexdigest(), u["textSha256"])
        self.assertEqual(["Results"], next(u for u in first["units"] if u["xmlId"] == "p1")["sections"])

    def test_deep_structure_is_bounded(self):
        raw = SYNTHETIC.replace(b'<p id="p1">', b'<sec>' * 70 + b'<p id="p1">').replace(b'</p>\n<table-wrap', b'</p>' + b'</sec>' * 70 + b'\n<table-wrap')
        with self.assertRaisesRegex(ValueError, "STRUCTURE_LIMIT"): build(raw)

    def test_deep_inline_prose_uses_structure_limit_not_recursion_failure(self):
        raw = SYNTHETIC.replace(b'Mother was tested.', b'<italic>' * 1200 + b'Mother was tested.' + b'</italic>' * 1200)
        with self.assertRaisesRegex(ValueError, 'STRUCTURE_LIMIT'): build(raw)

    def test_front_matter_is_not_reported_as_case_body(self):
        raw = SYNTHETIC.replace(b'</article-meta>', b'<funding-group><p>Not a case observation.</p></funding-group></article-meta>')
        unit = next(u for u in build(raw)["units"] if u["text"] == "Not a case observation.")
        self.assertEqual("OTHER_TEXT", unit["kind"])

    def test_embedded_figure_does_not_remove_own_prose_or_merge_caption(self):
        raw = SYNTHETIC.replace(b'<p id="p1">Mother was tested. Father was not tested.</p>',
            b'<p id="p1">Own <italic>clinical</italic> statement. <fig><caption><p>Different figure statement.</p></caption></fig>'
            b' After figure <xref rid="r1">1</xref>. <table-wrap><table><tr><td>Separate table</td></tr></table></table-wrap>'
            b' Final prose.</p>')
        document = build(raw)
        unit = next(u for u in document['units'] if u['xmlId'] == 'p1')
        self.assertEqual('Own clinical statement. After figure 1. Final prose.', unit['text'])
        self.assertEqual(1, sum(u['text'] == 'Different figure statement.' for u in document['units']))
        self.assertEqual(1, sum(u['text'] == 'Separate table' for u in document['units']))
        citation = {'sourceSha256': document['sourceSha256'], 'unitId': unit['unitId'],
                    'start': 0, 'end': len(unit['text']), 'quote': unit['text']}
        self.assertEqual('CITATION_MATCHED_SEMANTICS_NOT_VERIFIED', cite(document, citation, raw))

    def test_nested_table_and_second_article_cannot_merge_case_identities(self):
        with self.assertRaisesRegex(ValueError, "NESTED_TABLE_NOT_SUPPORTED"):
            build(SYNTHETIC.replace(b'<td>A</td>', b'<td><table><tr><td>B</td></tr></table></td>'))
        with self.assertRaisesRegex(ValueError, "SUBARTICLE_REQUIRES_SEPARATE_IDENTITY"):
            build(SYNTHETIC.replace(b'</article>', b'<sub-article><body><p>Different cases</p></body></sub-article></article>'))


if __name__ == "__main__":
    unittest.main()
