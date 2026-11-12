# PM2 本地外显子接入

此入口复用正式 Exomiser 排名、用户选择 Top-N、共同归一化和保存链。运行 scope 为 `LOCAL_COMPUTATIONAL_BATCH1`；不会修改候选排名，也不会联网补查。旧 CURRENT 的 BA1/BS1 配置不受影响。

## 资源

- 原变异记录：本地 gnomAD 4.1 GRCh38 EXOMES 分染色体 VCF BGZF + TBI，提供精确等位基因是否出现、原生 FILTER/区间 QC、AC/AN/AF/FAF 等。
- 检测量：[官方 all-sites TSV](https://gnomad.broadinstitute.org/news/2024-04-gnomad-v4-1)，目录 `local/resources/gnomad/all-sites/4.1/exomes/`。原文件保留不改，1,149,738,480 bytes；官方 MD5 `ee71ce1ccfa5c1d9dd86e1ee1b1d11e2`。
- `all-sites.sparse-v1.bin` / `all-sites.sparse-v1.manifest.json` 是本项目索引与绑定回执，不是官方索引。一次完整 BGZF CRC、顺序/字段检查后建立，每 4096 行一个定位点；每次查询最多扫描 4097 行，无 Hail/WSL/外部 tabix。
- 不下载 genomes all-sites，不声称 EXOMES 覆盖全基因组。文件只有全体样本 adjusted AN 及四个区域标记，没有逐祖源 AN。

## 判定路线

1. 正式来源与归一化成功后，用新查询键读取本地 VCF。查询失败不能当成没有记录。
2. **有记录**：沿原精确疾病/遗传模式政策判断；保留六份已显式采用的本地政策及其真实阈值。FILTER/区间 QC 不合格即明确 NE。未知背景没有通用 AF 阈值，不能随意给数。
3. **没有精确等位基因记录**：常染色体 GRCh38 SNV 才补查 all-sites。核对同位置、同发布数据、原始 AN 和全部区域标记。源专规允许缺失判定时依原检测条件；明确不允许或尚未采用的专规不被通用分支绕过。
4. **没有已知专规的背景**：显式开启项目本地通用“未检出”分支时，要求 adjusted AN > 2000 且四个 capture/calling 排除标记全为 false，才给本地 PM2 Supporting。来源为 [2015 一般建议](https://www.nature.com/articles/gim201530) 的 >1000 个体规模及 [ClinGen Supporting 建议](https://www.clinicalgenome.org/docs/pm2-recommendation-for-absence-rarity/)。这是项目的 EXOMES 限定采用，**不证明祖源匹配、全 gnomAD 未见或临床等效**。AN 为经基因型质控的有效等位基因数，不能表述成每位样本均已验证 DP≥20。
5. indel/MNV、性染色体、无检测位置、AN 不足、区域排除、文件失效、知识缺项分别保存具体原因。结果完整保存，GET/重复请求读取旧结果；新数据或配置身份变化不能复用旧评价。

## 配置和资源准备

默认不开启新科学路线。已有服务启动配置设置 `variant-analysis.acmg-business.acceptance-scope=LOCAL_COMPUTATIONAL_BATCH1`、`enabled=true`，并沿用已有来源/参考/背景绑定；不要仅设置这些键而跳过来源资格。

| 属性（前缀 `variant-analysis.acmg-business.`） | 含义 |
| --- | --- |
| `local-batch-pm2-adoption-file` / `-sha256` | 既有六份本地政策的完整采用文件及原始字节 SHA |
| `local-batch-pm2-all-sites-file` / `-sha256` | **manifest** 绝对路径及 SHA；不是大 BGZF 路径 |
| `local-batch-pm2-general-absence-enabled` | 是否显式采用上述通用本地未检出条件 |

对应环境变量为 `VARVIS_PM2_LOCAL_ADOPTION_FILE`、`VARVIS_PM2_LOCAL_ADOPTION_SHA256`、`VARVIS_PM2_ALL_SITES_MANIFEST`、`VARVIS_PM2_ALL_SITES_MANIFEST_SHA256`、`VARVIS_PM2_GENERAL_ABSENCE_ENABLED`。本地人口查询使用 `variant-analysis.gnomad.local-population-in-process-enabled=true`。

一次性索引由 `org.nbscn.varvis.variantanalysis.provider.GnomadLocalAllSites` 的 main 执行，参数依次为原 BGZF、输出 index、输出 manifest、**已核实的原 BGZF SHA-256**。必须使用项目 Maven classpath，不覆盖既有输出；构建完把 manifest SHA 绑定至服务配置。重复运行应用只加载小索引并检查当前资源身份，不重复全文件摘要或扫描。

## 维护与定位

- `GnomadLocalAllSites`：读取与索引；`Pm2LocalExomesProfile`：本地范围与专规选择。
- `Pm2LocalExomesRuntime` → `GnomadPopulationCriterionEvaluator.evaluatePm2LocalExomes`：取证与判定。
- `LocalEvidenceBatchConfiguration` / `LocalEvidenceBatchRuntime`：正式 Top-N 集成、配置身份、结果投影。
- 页面沿已有完整 JSON 导出；“人口记录查询”和“检测量证明”分别展示，不把 NO_RECORD 写成 AF=0。
- 聚焦测试：`GnomadLocalAllSitesTest,Pm2LocalAbsenceTest,Pm2LocalExomesTest,Pm2LocalQualificationTest,LocalEvidenceBatchConfigurationTest,LocalEvidenceBatchBusinessTest`；前端 `scripts/local-batch1-contract-smoke.mjs`。

实际下载、校验、真实查询和完成范围见 [2026-10-11 接入记录](../../docs/execution/PM2_LOCAL_COMPLETION_2026-10-11.md)。
