"""Build the reviewed PM2 policy pack from a bounded, retained official CSpec capture.

This is an offline compiler, not a natural-language rule interpreter. Only the
explicitly reviewed expressions below are executable. Other published scopes
remain visible and block unsafe fallback. No candidate coordinates are inputs.
"""
import argparse
import hashlib
import json
from pathlib import Path


def sha(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     separators=(",", ":")).encode()).hexdigest()


# Reviewed PM2 expressions, not gene eligibility gates. Scope comes from the
# versioned official RuleSet. Values are fractions, counts are integer alleles.
REVIEWED = {
    "GN017": ("3.1", "FAF", "0.000003", "LESS_THAN_OR_EQUAL", None),
    "GN085": ("4.0", "FAF", "0.000003", "LESS_THAN_OR_EQUAL", None),
    "GN114": ("2.2", "FAF", "0.0001742", "LESS_THAN", 0),
    "GN116": ("2.2", "FAF", "0.00003266", "LESS_THAN", 0),
    "GN119": ("2.2", "FAF", "0.00004129", "LESS_THAN", 0),
    "GN121": ("2.3", "FAF", "0.000115", "LESS_THAN", 0),
    "GN123": ("2.2", "FAF", "0.000102", "LESS_THAN", 0),
    "GN124": ("2.2", "FAF", "0.0000588", "LESS_THAN", 0),
    "GN147": ("2.0", "ALLELE_COUNT", "1", "LESS_THAN_OR_EQUAL", None),
    "GN148": ("1.0", "ALLELE_COUNT", "1", "LESS_THAN_OR_EQUAL", None),
    "GN150": ("2.0", "ALLELE_COUNT", "1", "LESS_THAN_OR_EQUAL", None),
}
# Any changed reviewed source, even at the same version, requires renewed review.
REVIEWED_SOURCE_SHA256 = {
    "GN017": "5076b6036f8d1bce84bac1813eefef3625f7a1d58e9519dce72648e98631cb07",
    "GN085": "1762c47d7309285054c45c8931549782db4b7b12854312bd1e3ed810e4ec57df",
    "GN114": "b35687f326e78135c404c815601de955b701993464a5cd7541478f0df23ec313",
    "GN116": "19bf22e15a2c12d2b58f0c2647edddf62f7f12bc6cec8c3e29d3afbfabff1f0b",
    "GN119": "dfbfb10b06c4f770ce014fb17f68241fb3d268605f6b7b7602df4199564577d7",
    "GN121": "e97786c230a8b6bfbb4df452b91c92f1ad191fb3c0348a552cfe30c38f41440a",
    "GN123": "41dafe044af07e0dc927c1672eae7b45f43eb9425f87cca3bb0c5ce89990daa0",
    "GN124": "c5561e871044640d35e4c918aaf16fbcb458c9f0723c1d8b5204946c248da02b",
    "GN147": "48d8efea9a3cebc3666d3f43055f98c05512864db667b75cd998613fd9613649",
    "GN148": "dce7a73a616ed90b6909e29157f55cc29cca990894a6d72ef5230f1501b988f2",
    "GN150": "c1c3c70a9828809ce9d59ebc0b28be9adcc7515897bd5d9b85214ec680e08334"
}
MOI = {"Autosomal Dominant": "AUTOSOMAL_DOMINANT",
       "Autosomal dominant inheritance": "AUTOSOMAL_DOMINANT",
       "Autosomal Recessive": "AUTOSOMAL_RECESSIVE",
       "Autosomal recessive inheritance": "AUTOSOMAL_RECESSIVE"}


def build(capture, resources):
    previous = json.loads((resources / "gnomad-population-input-profile-v2-pm2-vhl.json").read_text(encoding="utf-8"))
    profile = json.loads(json.dumps(previous))
    profile["profileVersion"] = "2.3.0"
    profile["asOf"] = "2026-09-09"
    profile["lineage"] = {"predecessorProfileId": previous["profileId"],
                          "predecessorProfileVersion": previous["profileVersion"],
                          "predecessorProfileSha256": sha(previous)}
    profile["datasetSemantics"]["generalCriterionDatasetReadiness"]["reasonCodes"] = ["PM2_CONTEXT_POLICY_REQUIRED"]
    rule = profile["evaluation"]["criteria"][2]["populationRule"]
    contract = rule["policyRecordContract"]
    contract["requiredFields"].remove("maximumAlleleFrequency")
    contract["exactlyOneOf"] = ["maximumAlleleFrequency", "maximumAlleleCount"]
    contract["alleleCountRequiredFields"] = ["maximumAlleleCount", "minimumObservedAlleleNumber"]
    rule["jointHitRequirement"]["metricSelectionReason"] = "Versioned PM2-specific policy; never inferred from BA1 or BS1."
    snapshot = rule["policySnapshot"]
    snapshot.update(snapshotId="pm2-cspec-context-policies-2026-09-09", version="2.0.0")
    snapshot.pop("sha256")
    snapshot["defaultResolution"] = {
        "status": "REVIEW_REQUIRED", "reasonCode": "PM2_DISEASE_FREQUENCY_PARAMETERS_REQUIRED",
        "requiredInputs": ["PM2-specific frequency/callability policy with authoritative disease parameters"],
        "universalNumericalThreshold": False,
        "populationEvidencePreparedIndependently": True,
    }
    source_rows, scopes = [], []
    releases = snapshot["records"][0]["resourceReleaseBindings"]
    vhl = snapshot["records"][0]
    vhl["version"] = "1.1.0"
    vhl["scope"]["variantType"] = "NORMALIZED_SMALL_SEQUENCE_VARIANT"
    # Explicit VHL no-FAF permission is bounded here to a verified singleton.
    vhl["missingFafPolicy"] = {"mode": "VERIFIED_SINGLETON", "maximumAlleleCount": "1"}
    vhl["limitations"] = ["Absence requires an independently justified callability policy; no universal AN threshold is inferred.",
                           "Other no-FAF cases require review; the singleton branch requires AC=1 and consistent population measurements."]
    for path in sorted((capture / "cspec").glob("GN*.json")):
        raw = path.read_bytes()
        doc = json.loads(raw)["data"]
        meta = doc["entContent"]
        ident, version = path.stem, meta["version"]
        if not any(s.get("current") and s.get("name") == "Released" for s in meta.get("states", [])):
            continue
        if meta.get("legacyReplaced") or meta.get("legacyFullySuperseded"):
            continue
        pm2 = [q["entContent"] for q in doc["ld"].get("CriteriaCode", []) if q["entContent"].get("label") == "PM2"]
        genes = {g["entId"]: g.get("entContent", {}).get("HGNC", {}).get("hgnc_id") for g in doc["ld"].get("Gene", [])}
        rules = [r["entContent"].get("genes", []) for r in doc["ld"].get("RuleSet", [])]
        projection = {"specificationId": ident, "version": version, "title": meta["title"],
                      "approvedOn": meta.get("approvedOn"), "sourceUrl": f"https://cspec.genome.network/cspec/ui/svi/doc/{ident}?version={version}",
                      "apiSourceSha256": hashlib.sha256(raw).hexdigest(), "genes": genes,
                      "ruleSetScopes": rules, "pm2": pm2}
        source_rows.append(projection)
        authority = {"authoritySourceId": "authority-clingen-" + ident.lower() + "-" + version,
                     "authorityVersion": version, "snapshotSha256": sha(projection),
                     "sourceUrl": projection["sourceUrl"], "specificationId": ident,
                     "retrievedOn": "2026-09-09", "scope": "PM2 only; exact official RuleSet scope"}
        reviewed = REVIEWED.get(ident)
        if reviewed and sha(projection) != REVIEWED_SOURCE_SHA256[ident]:
            raise ValueError(f"{ident}: reviewed source content changed")
        if reviewed and version != reviewed[0]:
            raise ValueError(f"{ident}: reviewed version changed")
        for group in rules:
            for gene in group:
                for disease in gene.get("diseases", []):
                    scope = {"specificationId": ident, "version": version,
                             "hgncId": genes.get(gene["gene"]), "symbol": gene["gene"],
                             "mondoId": disease.get("preferredMondoId"),
                             "moi": MOI.get(disease.get("preferredModeOfInheritance")),
                             "sourceMoi": disease.get("preferredModeOfInheritance"),
                             "sourceUrl": projection["sourceUrl"], "authoritySha256": sha(projection)}
                    scopes.append(scope)
                    if not reviewed or not scope["hgncId"] or not scope["mondoId"] or not scope["moi"]:
                        continue
                    _, kind, value, comparator, hom = reviewed
                    policy = {"policyId": f"PM2_{ident}_{scope['hgncId']}_{scope['mondoId']}_{scope['moi']}",
                              "version": "1.0.0", "gene": {"hgncId": scope["hgncId"]},
                              "disease": {"mondoId": scope["mondoId"]}, "moi": scope["moi"],
                              "resourceReleaseBindings": releases, "authority": authority,
                              "scope": {"assembly": "GRCh38", "variantType": "NORMALIZED_SMALL_SEQUENCE_VARIANT", "strength": "SUPPORTING"},
                              "allowWellCoveredAbsence": False,
                              "allSitesCallability": {"reasonCode": "PM2_ABSENCE_DETECTION_POLICY_REQUIRED"}}
                    if kind == "FAF":
                        policy["maximumAlleleFrequency"] = {"metricId": "gnomad.joint.faf95max", "rawValue": value, "comparisonOperator": comparator, "unit": "fraction"}
                        policy["jointQualityRequirements"] = vhl["jointQualityRequirements"]
                        if hom is not None: policy["maximumHomozygoteCount"] = str(hom)
                    else:
                        policy["maximumAlleleCount"] = value
                        policy["minimumObservedAlleleNumber"] = "2000"
                        policy["allowWellCoveredAbsence"] = True
                        policy["allSitesCallability"] = {"satisfactionMode": "ANY_INDEPENDENT", "independentlySufficientDatasets": ["EXOMES", "GENOMES"]}
                        for token, release in zip(["exomes", "genomes"], releases[1:]):
                            part = {"resourceId": release["resourceId"], "minimumAdjAlleleNumber": {"rawValue": "2000", "comparisonOperator": "GREATER_THAN_OR_EQUAL"}}
                            if token == "exomes": part["excludedWhenAnyFlagsTrue"] = ["outside_broad_capture_region", "outside_ukb_capture_region", "outside_broad_calling_region", "outside_ukb_calling_region"]
                            policy["allSitesCallability"][token] = part
                    snapshot["records"].append(policy)
        if reviewed: profile["contextPolicyAuthorityBindings"].append(authority)
    source_pack = {"schemaVersion": "pm2-cspec-reviewed-source-pack-v1", "retrievedOn": "2026-09-09", "sources": source_rows}
    snapshot["knownSpecificationScopes"] = scopes
    snapshot["sourcePackSha256"] = sha(source_pack)
    snapshot["sha256"] = sha(snapshot)
    # Missing optional frequency fields are assessed by the selected policy,
    # never globally coerced to zero or allowed to block other policy metrics.
    for field in profile["fieldContract"]["fields"]:
        if field["infoId"] not in {"AC_joint", "AN_joint", "AF_joint", "nhomalt_joint"}: field["required"] = False
    (resources / "pm2-cspec-reviewed-sources-20260909.json").write_bytes((json.dumps(source_pack, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    (resources / "gnomad-population-input-profile-v2-pm2-multigene.json").write_bytes((json.dumps(profile, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print("profileSha256", sha(profile), "sourcePackSha256", sha(source_pack), "policies", len(snapshot["records"]), "knownScopes", len(scopes))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--resources", type=Path, required=True)
    args = parser.parse_args()
    build(args.capture, args.resources)
