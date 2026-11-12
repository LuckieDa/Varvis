# PP3/BP4 共用 GENCODE 转录本材料

此入口补充所选候选的精确版本转录本模型，不授予预测政策、RNA 实验或临床使用资格。它复用现有归一化参考回执、`CodingTranscriptMapping`、`CodingMissenseResolver` 和原 VEP 注释；不重新运行 Exomiser/VEP，不联网查转录本，不按基因写规则。

## 构建与文件

使用 Code-local Python。输入必须是本机已有的完整 GENCODE GRCh38 GTF，输出必须是尚不存在的目录：

```powershell
python scripts/acmg-generalization/build_gencode_transcript_index.py `
  --source local/resources/gencode/gencode.v38.annotation.gtf `
  --output local/resources/gencode/derived/<new-unique-directory> --release 38
```

一次顺序读取 GTF，读取时计算源 SHA 并核前后大小/mtime；输出 `annotations.jsonl`、`index.tsv`、`excluded.jsonl`，最后写 `manifest.json`。失败或源变化不得启用输出。重复构建不得覆盖既有目录；先确认确需更新，不能为每次请求重建。

GTF 坐标为 1-based；外显子按 `exon_number` 的转录方向处理，CDS phase、起始/终止密码子和 UTR 坐标均参加结构检查。规则依据：[GENCODE 格式](https://www.gencodegenes.org/pages/data_format.html)、[v38 / Ensembl 104](https://www.gencodegenes.org/human/release_38.html)。负链逐外显子反向互补，跨外显子 CDS 不拆成互不相干的蛋白片段。非完整/readthrough、非标准翻译、非主染色体/MT、无精确 ENST/ENSP 版本或缺 HGNC 等记录写明排除原因。

源摘要的含义是 `MEASURED_LOCAL_SNAPSHOT_NOT_OFFICIAL_CHECKSUM`：记录本机源的实际字节，未声称独立核过官方发行摘要。模型中保留源 GTF SHA、原转录本块 SHA、派生记录 SHA、manifest pin 和来源链接。

## 接线

现有 `LOCAL_COMPUTATIONAL_BATCH1` 增加两项可选配置，默认空；当前日常配置未由本轮修改：

```text
VARVIS_GENCODE_TRANSCRIPT_INDEX_DIRECTORY=<包含 manifest.json/index.tsv/annotations.jsonl 的绝对路径>
VARVIS_GENCODE_TRANSCRIPT_INDEX_MANIFEST_SHA256=<manifest 原始字节 SHA-256>
```

对应 `variant-analysis.acmg-business.gencode-transcript-index-directory` / `gencode-transcript-index-manifest-sha256`。已有 `transcript-material-directory` 可以同时配置：精确模型存在时继续用原模型及原 pin；只有模型不存在才查 GTF。旧模型损坏、错配不会触发回退。也支持 GTF-only。

必须保留原参考的 resource-root、receipt-file/SHA/locator、FASTA、FAI、profile 和 SHA 配置。候选使用 `12`、FASTA 使用 `chr12` 时，须显式提供经 FAI 核对的 `variant-analysis.normalization.grch38-contig-alias-file`；本代码不会猜映射。此次验证只在任务配置加入 `1..22/X/Y → chr1..chr22/chrX/chrY`，正式配置未改。

运行时先核 manifest/index SHA 与文件元数据，通过索引只读取所选转录本一条注释并核其 SHA，再读取合格参考中的外显子小片段。`annotations.jsonl` 不在每次初始化做全量 SHA；完整根信任来自受 pin 保护的 index 和每条记录 SHA。数据/索引/manifest 的大小、mtime、文件身份每次核对，来源变更不能复用旧材料。参考缓存身份含资格、索引/别名身份；每次缓存命中仍重新取得参考见证并核当前变异与 VEP。

技术上限：200,000 个索引条目；index 32 MB、data 512 MB、单记录 1 MB；每模型最多 1,000 外显子/1,000,000 bases；单次参考片段最多 100,000 bases；派生模型缓存 128 项。协作式取消不清除中断状态；不声称硬中断操作系统磁盘 I/O。

## 支持与停止条件

- 现行调用范围：原生 GRCh38、所选精确版本 ENST、普通编码错义 SNV；模型可存跨外显子结构，但不扩大原 `CodingMissenseResolver` 的变异类型范围。
- 不自动用邻近转录本版本、RefSeq 替代、GRCh37 转换模型或基因默认转录本。
- 输出 `READY / EXACT_MODEL_REFERENCE_AND_VEP_AGREE` 表示技术材料一致。受审政策仍须满足原有精确模型及应用绑定；新 `GENCODE_*` 模型不会自动继承旧模型政策。
- 缺版本、参考未合格、别名缺失、基因/等位基因/VEP 不一致、资源变化、翻译不合法均明确拒绝。无模型不是低分，也不是 BP4。
- 本材料来自 GTF 与基因组参考重建，不是独立 RNA 序列验证；不可替代剪接或正常 RNA 证据。

## 最小验证入口

```powershell
python -m unittest discover -s scripts/acmg-generalization -p test_build_gencode_transcript_index.py
# 在 varvis-server 下，激活 Code-local 工具后：
mvn -o "-Dtest=GencodeTranscriptIndexTest,CodingTranscriptMaterialSupplyTest,SelectedPredictionRoutingTest,LocalEvidenceBatchConfigurationTest,PredictionMechanismSummaryTest" test
```

涉及保存/profile 时再加 `LocalEvidenceBatchBusinessTest,LocalEvidenceBatchRuntimeTest`。前端用 `local-batch1-contract-smoke.mjs`；真实结果和模型验证归属见[本轮总结](../../docs/execution/PP3_BP4_TRANSCRIPT_SUPPLY_EXPANSION_2026-10-11.md)，无需为阅读此文重跑历史验收。
