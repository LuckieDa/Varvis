package org.nbscn.varvis.variantanalysis.provider;

import java.util.List;

/** Restart-bound formal business scope; expanding it requires an explicitly reviewed delivery. */
public final class AcmgBusinessRuleScope {
    public static final String LOCAL_BATCH1 = "LOCAL_COMPUTATIONAL_BATCH1";
    public static final String LOCAL_BATCH1_DECLARATION = "ordinary-local-computational-batch1-v1";
    public static final String UNIFIED_FOURTEEN_DECLARATION = "ordinary-unified-local-fourteen-v1";
    public static final String UNIFIED_ELEVEN_DECLARATION = "ordinary-unified-local-eleven-v1";
    public static final String UNIFIED_LOCAL_DECLARATION = "ordinary-unified-local-eight-v1";
    public static final String PP4_DEVELOPMENT = "PP4_PHENOTYPE_DEVELOPMENT";
    public static final String PP4_DECLARATION = "ordinary-pp4-phenotype-v1";
    public static final String BS4_DEVELOPMENT = "BS4_NONSEGREGATION_DEVELOPMENT";
    public static final String BS4_DECLARATION = "ordinary-bs4-nonsegregation-v1";
    public static final String PP1_DEVELOPMENT = "PP1_SEGREGATION_DEVELOPMENT";
    public static final String PP1_DECLARATION = "ordinary-pp1-segregation-v1";
    public static final String BP2_DEVELOPMENT = "BP2_PHASE_DEVELOPMENT";
    public static final String BP2_DECLARATION = "ordinary-bp2-phase-v1";
    public static final String PM3_DEVELOPMENT = "PM3_IN_TRANS_DEVELOPMENT";
    public static final String PM3_DECLARATION = "ordinary-pm3-in-trans-v1";
    public static final String PM6_DEVELOPMENT = "PM6_DENOVO_DEVELOPMENT";
    public static final String PM6_DECLARATION = "ordinary-pm6-denovo-v1";
    public static final String PS2_DEVELOPMENT = "PS2_DENOVO_DEVELOPMENT";
    public static final String PS2_DECLARATION = "ordinary-ps2-denovo-v1";
    public static final String BP1_DEVELOPMENT = "BP1_MISSENSE_DEVELOPMENT";
    public static final String BP1_DECLARATION = "ordinary-bp1-missense-v1";
    public static final String PP2_DEVELOPMENT = "PP2_MISSENSE_DEVELOPMENT";
    public static final String PP2_DECLARATION = "ordinary-pp2-missense-v1";
    public static final String BP3_DEVELOPMENT = "BP3_INFRAME_DEVELOPMENT";
    public static final String BP3_DECLARATION = "ordinary-bp3-inframe-v1";
    public static final String PVS1_DEVELOPMENT = "PVS1_NMD_DEVELOPMENT";
    public static final String PVS1_DECLARATION = "ordinary-pvs1-nmd-v1";
    public static final String PM1_DEVELOPMENT = "PM1_REGION_DEVELOPMENT";
    public static final String PM1_DECLARATION = "ordinary-pm1-region-v1";
    public static final String PM5_DEVELOPMENT = "PM5_MISSENSE_DEVELOPMENT";
    public static final String PM5_DECLARATION = "ordinary-pm5-missense-v1";
    public static final String PS1_DEVELOPMENT = "PS1_MISSENSE_DEVELOPMENT";
    public static final String PS1_DECLARATION = "ordinary-ps1-missense-v1";
    private AcmgBusinessRuleScope() { }
    public static final List<String> LEGACY_TWO_RULE = List.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:PM2");
    public static final List<String> CURRENT = List.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:PM2", "ACMG_AMP_2015:BS1");
    public static final List<String> BS1_ACCEPTANCE = List.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:PM2", "ACMG_AMP_2015:BS1");
    public static final String BS1_DECLARATION = "ordinary-ba1-pm2-bs1-v1";
}
