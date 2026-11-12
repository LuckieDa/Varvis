"""Static Phase B contract checks; no service, database, or Exomiser process is started."""

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "varvis-server" / "src" / "main"
FRONTEND = ROOT / "varvis-client-pro" / "src" / "pages" / "varvis" / "ChromosomeVisualization"


class PhaseBExomiserContractTest(unittest.TestCase):
    def test_adapter_is_disabled_by_default_and_truth_free(self):
        adapter = (SERVER / "java/org/nbscn/varvis/variantanalysis/engine/"
                   "ConfiguredExomiserEngineAdapter.java").read_text(encoding="utf-8")
        properties = (SERVER / "java/org/nbscn/varvis/variantanalysis/config/"
                      "VariantAnalysisProperties.java").read_text(encoding="utf-8")
        self.assertIn("EXOMISER_DISABLED", adapter)
        self.assertIn("private boolean enabled;", properties)
        for forbidden in ("truthGene", "truthVariant", "truthRank", "truthMatches", "expectedRank"):
            self.assertNotIn(forbidden, adapter)

    def test_api_and_processor_expose_backend_engine_contract(self):
        response = (SERVER / "java/org/nbscn/varvis/variantanalysis/dto/"
                    "VariantAnalysisJobResponse.java").read_text(encoding="utf-8")
        processor = (SERVER / "java/org/nbscn/varvis/variantanalysis/service/"
                     "VariantAnalysisJobProcessor.java").read_text(encoding="utf-8")
        for field in ("engine", "engineVersion", "rankingMode", "engineStatus",
                      "engineUnavailableReason"):
            self.assertIn(field, response)
        self.assertIn("processExomiser", processor)
        self.assertIn("EXOMISER_ADAPTER_UNAVAILABLE", processor)

        input_guard = (SERVER / "java/org/nbscn/varvis/variantanalysis/service/impl/"
                       "VariantAnalysisServiceImpl.java").read_text(encoding="utf-8")
        self.assertIn("validateExomiserProductInput", input_guard)
        self.assertIn("Family-mode input is not available", input_guard)

    def test_product_configuration_defaults_to_exomiser(self):
        configuration = (SERVER / "resources/application.yml").read_text(encoding="utf-8")
        self.assertIn("default-engine: ${VARVIS_DEFAULT_ANALYSIS_ENGINE:EXOMISER}", configuration)

    def test_compressed_vcf_submission_uses_one_upload_and_private_tabix_preparation(self):
        service = (FRONTEND / "services/variantAnalysis.js").read_text(encoding="utf-8")
        drawer = (FRONTEND / "components/VariantAnalysisDrawer.jsx").read_text(encoding="utf-8")
        tabix = (SERVER / "java/org/nbscn/varvis/variantanalysis/service/"
                 "VcfTabixIndexValidator.java").read_text(encoding="utf-8")
        self.assertNotIn("vcfIndexFile", service)
        self.assertNotIn("vcfIndexFile", drawer)
        for contract in (
            "primary-vcf-input",
            "analysisRequestErrorMessage",
            "job.errorCode",
        ):
            self.assertIn(contract, drawer)
        for contract in ("prepareForExomiser", "normalizeGzipToBgzf", "createTabixIndex"):
            self.assertIn(contract, tabix)

    def test_engine_provenance_does_not_replace_the_product_tier(self):
        parser = (SERVER / "java/org/nbscn/varvis/variantanalysis/engine/"
                  "ExomiserOutputParser.java").read_text(encoding="utf-8")
        drawer = (FRONTEND / "components/VariantAnalysisDrawer.jsx").read_text(encoding="utf-8")
        self.assertIn('ENGINE_RANKED_TIER = "REVIEW"', parser)
        self.assertNotIn('applyRanking(\n                    "EXOMISER"', parser)
        self.assertIn("'Ranking engine'", drawer)
        self.assertIn("? 'REVIEW' : tier", drawer)


if __name__ == "__main__":
    unittest.main()
