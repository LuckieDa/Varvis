package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independent edit/phase expectations for commander review 8487d4f4. */
class CodingTranscriptAliasBindingTest {
    private static final String G = "a".repeat(64), H = "b".repeat(64), SOURCE_A = "c".repeat(64), SOURCE_B = "d".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodingTranscriptEdits.Reference reference;
    CodingTranscriptAliasBindingTest() {
        var fixture = new CodingTranscriptEditsTest(); var model = fixture.model(1);
        reference = CodingTranscriptEdits.bind(mapper, fixture.context(), model,
                new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(model), CodingTranscriptMaterialSupply.sha("ATGAAACCCGGGTTTTAA"));
    }
    private CodingTranscriptEdits.Edit splice(String id, String group, String source) {
        return new CodingTranscriptEdits.Edit(id, CodingTranscriptEdits.Origin.SPLICE_PROJECTION,
                6, 12, "", "CCCGGG", source, group, 0);
    }
    private CodingTranscriptEdits.Edit coding(String id, String group) {
        return new CodingTranscriptEdits.Edit(id, CodingTranscriptEdits.Origin.CODING_ALLELE,
                12, 15, "GCC", "TTT", SOURCE_A, group, 2);
    }
    private List<List<CodingTranscriptEdits.Edit>> permutations(List<CodingTranscriptEdits.Edit> input) {
        if (input.isEmpty()) return List.of(List.of());
        var all = new ArrayList<List<CodingTranscriptEdits.Edit>>();
        for (int i = 0; i < input.size(); i++) {
            var rest = new ArrayList<>(input); var first = rest.remove(i);
            for (var tail : permutations(rest)) { var row = new ArrayList<CodingTranscriptEdits.Edit>(); row.add(first); row.addAll(tail); all.add(row); }
        }
        return all;
    }
    private static Stream<Arguments> invalidGroups() {
        return Stream.of(Arguments.of(G, H, "GROUP_CONFLICT"), Arguments.of(G, "", "GROUP_MISSING"),
                Arguments.of("", H, "GROUP_MISSING"), Arguments.of("", "", "GROUP_MISSING"));
    }

    @ParameterizedTest @MethodSource("invalidGroups")
    void aliasConflictsCannotBeReducedOrHiddenByInputOrderOrFavourableIds(String firstGroup, String secondGroup, String status) {
        for (boolean favourableFirst : List.of(false, true)) for (boolean includeCoding : List.of(false, true)) {
            var first = splice(favourableFirst ? "a-source" : "z-source", firstGroup, SOURCE_A);
            var second = splice(favourableFirst ? "z-source" : "a-source", secondGroup, SOURCE_B);
            var original = new ArrayList<>(List.of(first, second)); if (includeCoding) original.add(coding("retained", G));
            // With coding G, an H/empty alias pair has both conflict and missing.
            String expectedStatus = includeCoding && firstGroup.isEmpty() && H.equals(secondGroup)
                    ? "GROUP_CONFLICT_AND_MISSING" : status;
            for (var order : permutations(original)) {
                var before = List.copyOf(order);
                var rejected = assertThrows(CodingTranscriptEdits.Rejected.class, () -> CodingTranscriptEdits.apply(reference, order));
                assertEquals("CODING_EDIT_SAME_ALLELE_BINDING_REQUIRED", rejected.reasonCode());
                assertEquals(expectedStatus, rejected.bindingStatus());
                assertEquals(original.size(), rejected.inputBindings().size());
                assertTrue(rejected.inputBindings().containsAll(original), "All original sources/groups must remain visible");
                assertEquals(before, order, "No mutation of supplied events");
                var snapshot = rejected.snapshot(mapper);
                assertEquals(original.size(), snapshot.path("inputBindings").size());
                assertFalse(snapshot.path("partialResultAvailable").asBoolean(true));
                assertFalse(snapshot.path("scientificQualificationGranted").asBoolean(true));
                assertThrows(UnsupportedOperationException.class, () -> rejected.inputBindings().clear());
                order.clear(); assertEquals(original.size(), rejected.inputBindings().size());
                snapshot.remove("inputBindings"); assertEquals(original.size(), rejected.snapshot(mapper).path("inputBindings").size());
            }
        }
    }

    @Test void sameGroupAliasesKeepDifferentSourcesButApplyTheirSequenceOnlyOnce() {
        var first = splice("a", G, SOURCE_A); var second = splice("z", G, SOURCE_B);
        for (var events : List.of(List.of(first, second), List.of(second, first))) {
            var result = CodingTranscriptEdits.apply(reference, events);
            assertEquals("ATGAAATTTTAA", result.alteredCds()); assertEquals(-6, result.delta());
            assertEquals(List.of(6), result.junctions());
            var facts = result.snapshot(mapper); assertEquals(2, facts.path("edits").size());
            var sources = new ArrayList<String>(); int applied = 0;
            for (var row : facts.path("edits")) { sources.add(row.path("evidenceBindingSha256").asText()); if ("APPLIED".equals(row.path("status").asText())) applied++; }
            assertEquals(1, applied); assertTrue(sources.containsAll(List.of(SOURCE_A, SOURCE_B)));
        }
    }

    @Test void threeCompatibleInputsHaveOneStableSequenceAndAllBindingsAcrossIdPermutations() {
        for (boolean swappedIds : List.of(false, true)) {
            var events = List.of(splice(swappedIds ? "z" : "a", G, SOURCE_A), splice(swappedIds ? "a" : "z", G, SOURCE_B), coding("coding", G));
            for (var order : permutations(events)) {
                var result = CodingTranscriptEdits.apply(reference, order);
                assertEquals("ATGAAAGCCTAA", result.alteredCds()); assertEquals(-6, result.delta()); assertEquals(List.of(6), result.junctions());
                assertEquals(List.of(1, 2, 3, 4, 5, 6, 0, 0, 0, 16, 17, 18),
                        java.util.stream.IntStream.rangeClosed(1, 12).map(result::alteredToReferenceBase).boxed().toList());
                assertEquals(3, result.snapshot(mapper).path("edits").size());
            }
        }
    }

    @Test void oneUnboundPhysicalEventAndNoEditRemainLegalButTwoUnboundAliasesDoNot() {
        assertEquals("ATGAAACCCGGGTTTTAA", CodingTranscriptEdits.apply(reference, List.of()).alteredCds());
        assertEquals("ATGAAATTTTAA", CodingTranscriptEdits.apply(reference, List.of(splice("single", "", SOURCE_A))).alteredCds());
        assertEquals("ATGAAACCCGGGGCCTAA", CodingTranscriptEdits.apply(reference, List.of(coding("single", ""))).alteredCds());
        var duplicated = List.of(splice("alias-a", "", SOURCE_A), splice("alias-b", "", SOURCE_B));
        assertEquals("GROUP_MISSING", assertThrows(CodingTranscriptEdits.Rejected.class,
                () -> CodingTranscriptEdits.apply(reference, duplicated)).bindingStatus());
    }

    @Test void aCodingEventRemovedBySplicingStillCannotHideAnUnrelatedBinding() {
        var removed = new CodingTranscriptEdits.Edit("removed", CodingTranscriptEdits.Origin.CODING_ALLELE,
                6, 9, "GCC", "CCC", SOURCE_B, H, 1);
        assertEquals("GROUP_CONFLICT", assertThrows(CodingTranscriptEdits.Rejected.class,
                () -> CodingTranscriptEdits.apply(reference, List.of(splice("source", G, SOURCE_A), removed))).bindingStatus());
    }
}
