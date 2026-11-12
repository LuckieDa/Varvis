# Windows RAW SpliceAI 的 Top-N 接线

入口仍是 `LOCAL_COMPUTATIONAL_BATCH1`，新路线默认关闭。不是官方 pysam CLI；复用原生环境、官方 1.3.1 模型及 `Annotator/get_delta_scores`，不改变算法、D=4999/M=0 或旧阈值。

`run_spliceai_native_business.py` 接收 `spliceai-native-selected-request-v1` JSON 批次，返回独立 `spliceai-native-business-result-v1`，不能代替旧 WSL 的 `spliceai-raw-batch-result-v1`。它保留全部原始 DS/DP、实际使用的注释行、精确转录本、派生注释文件 SHA、19,999 bp 参考窗口摘要、模型调用与运行指标。

## 显式配置

在 `variant-analysis.acmg-business` 下配置：

| 配置 | 含义 |
| --- | --- |
| `local-batch-native-splice-enabled` | 默认 false，显式选择此运行路线 |
| `native-splice-python-file` | 独立 Windows venv 的 `Scripts/python.exe` |
| `native-splice-adapter-file` | 本仓库 `scripts/e4_b3/run_spliceai_native_business.py` |
| `native-splice-binding-file` / `-sha256` | 当前环境候选身份 JSON 及原始字节 SHA-256；不是科学批准 |
| `native-splice-reference-fasta` | 原验证的 VEP GRCh38 参考及 FAI |
| `native-splice-timeout-seconds` | 默认 540，允许 1–600 秒；所有子批共享的 RAW 计算阶段预算，包含请求准备、排队、进程启动和输出核对；不是每批重新计时，也不是整个 ACMG 请求的总时限 |

同时需要现有 GENCODE 精确版本索引及 manifest pin、已核定参考回执/别名、固定专规范围目录及 pin。沿用原资源配置，不改变日常实例。先用 `--readiness-only --reference-fasta <path> --output <new-binding.json>` 生成独立身份，再核对并以 SHA 绑定隔离任务。身份包括实际包版本、模型/源码/注释、Keras 配置、适配器和参考元数据；不是对整份 VEP FASTA 的字节资格声明。Java 另用既有合格参考核对每个实际推理窗口。

版本：Java 调度/关联 `1.2.0`；runner 的 Python 绑定合同与 Python business adapter 仍为 `1.1.0`，本轮未改适配器、模型或绑定文件；共用 probe helper `1.0.3-probe`；预测摘要 `1.4.1`。旧1.0.x绑定仍拒绝沿用。调度版本、组合上限和总预算进入执行身份，不能复用旧调度版本的评价。helper 的原 probe 标签保留其来源，新业务身份单独包裹，不继承旧 Linux/WSL 身份。

## 采用范围

本轮只接原生 GRCh38 SNV 的错义、同义和非经典内含子后果。经典 ±1/2、超界、精确版本不存在、专规未核定、参考变化等返回明确原因。一次所选集合最多50个通过准备并去重的“等位基因＋精确转录本＋完整结构”组合，按原顺序每10个串行一批，最多5批；Python单批上限仍为10。超过50个则未启动并明确说明，不静默截断或补位。未通过准备的组合不占批次名额。相同组合只推理一次、各背景分别采用；同一等位基因对应不同转录本时分别推理，不能共享不相容的分数。

每批先独立核对返回数量、queryId、等位基因和该批注释摘要，整批结构核对通过才接纳原始记录；后续逐组合仍核参考、转录本、分数及采用条件。坏批次不能清空此前批次或向其他批次串入记录。普通批次失败在剩余预算内继续后续批次，不重试失败批；超时、取消或清理未确认则停止后续批次。晚于总期限的本批结果不接纳。已完成合格批次在模块结果中保留，失败/未执行组合有各自原因；这不改变外层用户取消请求时的保存语义。

关联须满足精确 GENCODE 转录本版本、基因/染色体/链、全部外显子与转录范围一致，且实际推理窗口与合格参考一致。从已校验索引取得所选结构，将1-based闭区间起点减1、终点保持，写入官方自定义注释TSV；负链不反转基因组坐标。官方 `Annotator` 解析后逐字段比较，再为每个请求建立仅目标行的视图，共享原模型与参考句柄。不会把官方旧注释的 gene symbol 改名为所选转录本，也不会混入同基因另一转录本或重叠基因的结果。

`selected-annotations.tsv` 与请求/完整输出保存在该次受控运行目录。新结果保留其SHA和精确结构；资源/源码/GENCODE索引身份继续进入请求缓存身份。官方支持自定义注释；ClinGen 2024-03-22 §5说明校准采用MANE注释、RAW、D=4999，并建议阈值通用使用。本项目为所选精确GENCODE转录本的本地研究采用，不声称重现了校准队列或证明全部非MANE转录本临床相容。来源和边界见本轮总结。

通过后复用 `SpliceAiBusinessRuntime` 现有通用 Supporting 区间和 `PredictionMechanismApplication`；完整 BP4 仍需所需机制全部满足。已有受审/专规路线优先，masked 观测不能代替 RAW。原前端摘要只增加安全解释，无新页面或公开接口。

## 进程和保存

复用 Windows Job owner，等待整个 Job 自然结束；不能仅凭 Python launcher 退出接纳。每批只获得全局剩余预算，总预算到期或中断终止该 Job，确认自有进程退出。强制清理沿用原最多2秒独立清理限额，不能把它算成额外求值时间。小型请求、输出、stderr/stdout 留在工作根目录供诊断，不额外删除历史原件。旧其他 provider 的进程策略不变。

普通业务保存完整私有事实及身份。浮点运行元数据以 JSON 文本保留，不进入科学数字规范化；公开摘要不暴露执行路径。资源/方法/配置身份改变会产生新的请求身份，精确重复和历史 GET 复用原保存结果，不重算模型。

## 最小验证

- Python：`python -m unittest discover -s scripts/e4_b3 -p 'test_spliceai_native*.py'`。
- Java：`NativeSpliceAiBatchingTest`、`NativeSpliceAiRuntimeTest`、`SelectedPredictionRuntimeTest`、`LocalEvidenceBatchConfigurationTest`；`NativeSpliceAiBatchRunnerTest` 在Windows显式提供 `-Dvarvis.test.nativePython=<已有python.exe>` 后测试真实进程超时，未提供则条件跳过，假适配器不执行科学模型。改共享材料时再加 `GencodeTranscriptIndexTest`；改保存链时加 `LocalEvidenceBatchBusinessTest`。
- 分批增量：[实现及验证](../../docs/execution/PP3_BP4_BOUNDED_SPLICE_BATCHING_2026-10-12.md)。真实模型的11–50组合性能仍未在本轮测量，边界/分批采用使用合成事实，进程控制为真实Windows执行。
- 最新真实运行和边界：[所选转录本注释供给](../../docs/execution/PP3_BP4_SELECTED_SPLICE_ANNOTATION_2026-10-12.md)。上一轮[初始接线](../../docs/execution/PP3_BP4_NATIVE_TOPN_INTEGRATION_2026-10-12.md)的旧注释不匹配记录保留为历史。
