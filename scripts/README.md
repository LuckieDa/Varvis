# Varvis 脚本导航

ACMG整批验收离线比较：`evidence/compare_acmg_acceptance.cjs`，固定原件、精确背景、证据状态/强度/原因与条件差异见[验收基准指南](../docs/guides/ACMG_ACCEPTANCE_BASELINE.md)。不联网、不调用模型、不运行评价；测试案例只在私有基准中，不进入生产规则。

PS2/PM6自动发现论文：`denovo/discover_public_literature.py`，输入、公开网络开关与缓存见[指南](../docs/guides/DENOVO_LITERATURE_DISCOVERY.md)。此阶段仅检索元数据，不调用模型或下载全文，本模块不直接接工作台，第三阶段工作器复用它。

第二阶段由 `denovo/acquire_public_literature.py` 接候选，自动取得开放正文／附件、保留表格原值与来源并登记公共文献库；[命令与限制](../docs/guides/DENOVO_LITERATURE_ACQUISITION.md)。默认无网络，但可登记本地／缓存资料；不会调用模型或评分。

按用途查找脚本。目录名称只表示职责或历史阶段，不表示当前服务已启用、资源已就绪或验收已通过。
当前项目状态与文档优先级见 [文档入口](../docs/README.md)。

<a id="reuse-entrypoints"></a>
第三阶段 `denovo/public_literature_workflow.py` 串联上述两步及版本化附件输入/引用核验，Java异步入口由显式确认触发；[工作台、配置与范围](../docs/guides/DENOVO_PUBLIC_LITERATURE_WORKFLOW.md)。当前本地仅缓存、模型关闭，失败预留不清零。

## 后续开发直接复用入口（2026-10-07）

本表只读核对入口与参数解析，不执行脚本。先按[模块地图](../docs/REPOSITORY_MAP.md#materials)及[证据卡](../docs/execution/ACMG_EVIDENCE_REUSE_MAP.md)选一条，不批量运行历史目录。数据/输出参数取当轮固定manifest及审核原件，旧机器路径不照抄。

| 目的 | 入口和参数来源 | 输出/副作用与执行前提 |
| --- | --- | --- |
| BA1目录采集/编译/发布 | [import_cspec_catalog.py](acmg-generalization/import_cspec_catalog.py)、[用法](acmg-generalization/import-cspec-catalog.md)、[语义编译](acmg-generalization/cspec_ba1_semantics.py)；compile用directory/capture-sha256/manifest，publish用candidate及SHA、review及SHA、output | 部分子命令联网/写候选或发布，不是只读探针。当前1.1.1候选已技术审核但未发布，不能执行旧发布Prompt或静默回退旧目录 |
| BS1批量知识与通用模型 | [bs1_knowledge.py](acmg-generalization/bs1_knowledge.py)、[用法与来源](acmg-generalization/bs1-knowledge.md)；fetch/compile，严格绑定来源RAW摘要 | fetch仅更新公开知识；运行期本地查询。疾病关联/流行病学/未结构化专规不自动成为可执行阈值，不自动诊断患者 |
| PVS1机制源事实准备 | [prepare_g2p_mechanisms.py](pvs1/prepare_g2p_mechanisms.py)、[本地材料指南](../docs/guides/PVS1_LOCAL_MATERIAL_SUPPLY.md)；input/expected-sha256/source-version/output | 只读固定本地G2P，写新UNREVIEWED候选，保留完整原字段；不联网、不生成执行政策、不批准LoF或临床外显子 |
| PM4/PS1/PM5材料 | Java CLI及`prepare`见[材料地图](../docs/REPOSITORY_MAP.md#materials)；`--manifest --manifest-sha256 --output` | 写独立输出及manifest/bundle，缺审批保持候选；不调用临床自动批准。最小测试类见证据卡 |
| 转录本模型 | [build_transcript_model.py](acmg-generalization/build_transcript_model.py)、[VEP exporter指南](acmg-generalization/vep-transcript-export/README.md) | 读取固定模型/序列并生成模型，VEP exporter路径有真实程序前提；普通annotation不自动成为临床转录本知识 |
| REVEL派生 | [build_revel_index.py](acmg-generalization/build_revel_index.py)、[指南](acmg-generalization/build-revel-index.md) | 处理发布原件/索引，可能大规模写入；现有固定资源应复用，不重新构建全库 |
| 后端工具与辅助 | [start-varvis-server.ps1](../varvis-server/scripts/start-varvis-server.ps1)、[convert_exomiser_output_to_baseline.py](../varvis-server/scripts/convert_exomiser_output_to_baseline.py)、[build_clinvar_comparator_sidecar.py](../varvis-server/scripts/build_clinvar_comparator_sidecar.py) | 分别启动服务、转换比较输入、派生sidecar；比较输入不是生产排名。只操作获准自有实例/新输出，不启动原服务 |
| 前端合同/组件 | [acmg-input-contract-smoke.mjs](../varvis-client-pro/scripts/acmg-input-contract-smoke.mjs)、[pp4-business-component-checks.mjs](../varvis-client-pro/scripts/pp4-business-component-checks.mjs)、[revel-application-contract-smoke.mjs](../varvis-client-pro/scripts/revel-application-contract-smoke.mjs) | 既有Node/依赖及脚本声明的fixture；合同/组件不等于真实浏览器/用户文件。本轮不运行；专用browser-smoke先读参数，不因名称自动开端口 |
| 迁移/readiness | [Test-VarvisMigrationReadiness.ps1](migration/Test-VarvisMigrationReadiness.ps1)、[Test-VarvisDatabaseReadiness.ps1](migration/Test-VarvisDatabaseReadiness.ps1) | 后者可能连接数据库；历史ready不能当当前ready，本任务禁止执行。Maven绑定独立关闭，不重做工具迁移 |

工具授权、Code-local/JDK/ECJ、H2和实际文件下载规则见[资源/工具索引](../docs/resources/README.md#tools)。

| 用途 | 目录 | 内容 |
| --- | --- | --- |
| 公开文献按证据选取 | [select_public_papers.py](denovo/select_public_papers.py)、[指南](../docs/guides/PUBLIC_PAPER_LIBRARY.md) | 只读指定证据索引、核原件摘要、合并去重；PS2/PM6可导出既有模型输入。无联网/模型调用，不授予科学资格 |
| PS2/PM6文献Prompt v2离线准备 | [prepare_literature_prompt.py](denovo/prepare_literature_prompt.py)、[指南](../docs/guides/DENOVO_LITERATURE_PROMPT_V2.md) | 复用公开源合同生成请求，或离线检查v2候选引用；独立版本、无执行开关、不可直接发布业务结果；后继bundle接线已完成工程验证，真实新版小目标响应及病例业务接收已限定验证；整篇、附件病例对应和新论文自动采用仍PARTIAL |
| PS2/PM6新版候选接收 | [bundle_candidate_intake.py](denovo/bundle_candidate_intake.py)、[qualify_bundle_candidates.py](denovo/qualify_bundle_candidates.py)、[指南](../docs/guides/DENOVO_PUBLIC_LITERATURE_WORKFLOW.md) | 精确原件与响应离线复核、同篇病例归并及既有业务资格；生成接收文件不等于批准，新论文不自动进入生效索引 |
| PS2/PM6公开文献模型试验 | [run_model_extraction.py](denovo/run_model_extraction.py)、[指南](../docs/guides/DENOVO_GEMINI_PILOT.md) | 默认关闭、显式执行、公开来源；预算／缓存／原文重核。固定真实模型科研链已验证；广泛文献、真实PM6阳性及日常部署仍有限制 |
| 环境迁移与检查 | [migration](migration/) | 本地路径迁移、数据库及资源 readiness；检查输出仍在 `local/migration/` |
| 远程开发入口 | [remote](remote/) | 使用 Code-local 私有 SSH 配置、VS Code profile 与扩展启动 Remote-SSH |
| 科学验证 | [benchmark](benchmark/) | 数据集准备、审计及基准验证辅助脚本；以具体脚本和协议的范围为准 |
| 证据协议与校验 | [evidence](evidence/) | evidence-v2 合同、证据校验工具 |
| ACMG 扩展与普通候选流程 | [acmg-generalization](acmg-generalization/)、[ordinary_acmg](ordinary_acmg/) | 规范目录导入、资源准备和普通候选验证 |
| 条款及预测证据 | [bs1](bs1/)、[pm1](pm1/)、[pm2](pm2/)、[ps1_pm5](ps1_pm5/)、[pvs1](pvs1/)、[revel](revel/) | 各条款或预测来源的准备、核查和验收；适用范围由各脚本及正式记录约束 |
| 功能证据与联合验证 | [functional](functional/)、[integration_bs1_ps3](integration_bs1_ps3/) | 功能证据及 BS1/PS3 联合验证 |
| 演示与材料生成 | [demo](demo/)、[documents](documents/) | 演示辅助工具和历史教学材料生成脚本 |

## 保留原路径的阶段脚本

以下目录保留阶段编号，便于历史报告、测试和调用方继续定位。应先查相关验收记录，
再阅读目标脚本的参数和前提；不要批量运行整个目录。

| 阶段 | 目录 |
| --- | --- |
| 资源验证与探测 | [e2](e2/)、[e3](e3/)、[e3_c2r](e3_c2r/) |
| 预测、ClinVar、PVS1 和人群规则验证 | [e3_c1](e3_c1/)、[e3_c3](e3_c3/)、[e3_k1b](e3_k1b/)、[e3_l1a](e3_l1a/)、[e3_p1a](e3_p1a/)、[e3_p1b](e3_p1b/) |
| 资源绑定与上下文预检 | [e4_a1a](e4_a1a/)、[e4_a1b](e4_a1b/)、[e4_a2a](e4_a2a/)、[e4_a2b](e4_a2b/) |
| 候选输入及来源验证 | [e4_a3a](e4_a3a/)、[e4_a3b](e4_a3b/)、[e4_a3c](e4_a3c/) |
| 业务运行与面板验证 | [e4_b1](e4_b1/)、[e4_b3](e4_b3/) |

前端专用验证仍在 [前端 scripts](../varvis-client-pro/scripts/)，后端启动与运维辅助工具仍在
[后端 scripts](../varvis-server/scripts/)。新增脚本优先沿用已有用途目录。

## 阶段路径的保留理由

2026-09-14 对受版本管理的脚本、测试和文档进行了定向引用检查。以下是实际存在的调用关系示例，
不是当前业务启用清单：

| 被引用目录 | 调用或复现入口示例 |
| --- | --- |
| `e3_p1a/` | `bs1/validate_hnf1a_frequency.py` 及多项 E3 证据校验模块依赖其规则注册表校验 |
| `e3_c1/`、`e4_a2a/` | `e4_a2b/verify_alphamissense_dispatch.py` 引用预测证据和规范预检逻辑 |
| `e3_c2r/` | 后端 `IntronicScopeBusinessSmokeTest` 引用 SpliceAI 阶段探测脚本 |
| `e4_a3a/` → `e4_a3b/` → `e4_a3c/` | 候选、原生 SNV 来源和显式病例输入验证之间仍有跨目录调用 |
| `e4_a3c/` | `ordinary_acmg/verify_ordinary_run.py`、`e4_b1/verify_business_run.py` 复用显式输入校验 |
| `e3/`、`e4_a1a/`、`e4_a1b/`、`e4_b3/` | 正式阶段报告或用途目录的说明保留其原始复现路径 |

因此本轮统一导航、保留脚本路径和实现。其他阶段目录中的自测、冻结校验器和历史资源探测工具也按原范围保留，
不把“当前未发现外部调用”当作可删除依据。后续新增脚本按上方用途表放置；
归档与生成文件的位置规则见 [仓库维护说明](../docs/REPOSITORY_MAINTENANCE.md)。
