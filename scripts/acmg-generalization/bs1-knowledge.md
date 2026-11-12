# BS1 批量知识与通用疾病模型

当前快照（2026-10-09）：`local/resources/acmg/bs1-af-intervals-20261009/bundle.json`，SHA `8a69e91de4bba06324f34ac7c97203b532bda90bc82a1138657347cc5e3ff892`。29条知识、35个可选择代表背景；原24政策/3669参数摘要不变。34输入中32份为复用原件，2份为明确署名的本地技术审阅/资格数据。原服务未重启。下文旧快照路径保留历史归属。

### 明确AF区间编译

`translation=REVIEWED_TARGET_AF_INTERVAL_V1` 复用原EXPERT_CLAUSE_REVIEW和QUALIFICATION_REVIEW，不新增疾病评价器。需要：

- `intervalExpression`：在完整单Strong条款中唯一精确出现的两端表达式；例如 `greater than or equal to 0.0005 but less than 0.001` 或 `≥0.0005 (0.05%) and <0.001 (0.1%)`。
- `lowerBound/upperBound`：分别为`{operator,fraction}`；下限仅GT/GTE，上限仅LT/LTE，字符串分数须与原文及括号百分数一致，0<下限<上限<1。between等端点未知措辞不推定。
- 原有完整实体SHA、范围、原文、交叉规则/条件/例外审阅及`populationStatistic`保持。`sourceMetricExpression`只能为AF/allele frequency或MAF/minor allele frequency的配对原表述；不是FAF。
- `sampleSizeBasis=SOURCE_STATED`时，`eligibleAnExpression`须在原文精确出现，支持≥/>=/>或at least N observed alleles；数值前缀不能假冒完整数值。`LOCAL_ENGINEERING_MINIMUM`只允许明确采用的GTE 2000，禁止伪造原文表达式。两种均需非空`sampleSizeReview`，写明来源要求或本地工程保护。

运行仍按合格祖源最大AC/AN比较完整区间，超上限不能改挑更低祖源；新采用必须明确这个本地范围。MAF不得取补数，缺字段/质量问题返回NE，不把上限外NOT_MET解释为致病。完整说明见[阶段记录](../../docs/execution/BS1_SHARED_AF_INTERVAL_COMPLETION_2026-10-09.md)，最小新增测试`test_bs1_expert_interval.py`。此合同处理确定的频率区间，不是通用模型参数不确定区间。

当前解释快照（2026-10-09）：`local/resources/acmg/bs1-parameter-supply-20261009/bundle.json`，SHA `845d9226f1aba8c4d2aed45e476be2a533ca86319c3051dba32f13a0155db2f5`；同一批32源和24政策不变，30个代表背景仍可选择。import-v3在原bundle-v1背景中增加可选`parameterSupply`，不改变旧schema读取能力；旧无摘要明确显示未记录。新增摘要含精确scope、固定来源边界、四字段状态／理由和每字段最多3个源示例，计数说明省略项；完整事实仍在参数准备sidecar。摘要`modelExecutable`、`scientificallyAdopted`必须为false，不作为模型参数；这两个标记不否认另外已采用的专家政策。Java检查摘要与源表、背景和版本一致；身份包括摘要版和整份bundle SHA。不得以旧SHA读取新内容，也不向历史记录补写当前资料。

本轮[完整记录](../../docs/execution/BS1_PARAMETER_SUPPLY_EXPLANATION_2026-10-09.md)。重点测试：`test_bs1_parameter_supply.py`；`Bs1ParameterSupplyTest`的全背景比较需同时传`bs1.knowledge.test-file`、`bs1.knowledge.test-sha`和`bs1.knowledge.previous-file`；业务测试可用`bs1.knowledge.business-output`导出合成保存原件，再用`node scripts/bs1-parameter-supply-component-check.mjs <saved.json> <不存在的输出目录>`检查实际保存组件。后者是只读合成页面，不会提交业务或下载文件。下方既有批次路径为历史。

当前数据采用（2026-10-09）：最终快照为 `local/resources/acmg/bs1-qualified-batch-20261009/compiled-02/bundle.json`，本机路径／SHA已经更新，未重启原服务。GN021采用五大陆祖源目标AF、AN>2000；GN148保留FAF95及AC>1／AN≥2000，知识24条、可选择30背景。旧22记录完整保持。复用CURATED_RECORDS增加完整政策条件，再由QUALIFICATION_REVIEW绑定完整源与内容；数值单位须用现有合同的`alleles`，不要用`count`。原compiled-01为被拒绝的中间产物；下文其他批次路径为历史。详见[本批记录](../../docs/execution/BS1_QUALIFIED_KNOWLEDGE_BATCH_2026-10-09.md)。

本模块在现有本地 BS1 上补充数据驱动的知识入口。新增基因／疾病不需要新增 evaluator 类；添加有来源的结构化记录即可复用原有候选归一化、同身份人口事实、确认、评价和保存链。关系资料、待采用参数与可执行政策是不同层级，批量导入不会自动批准科学阈值。

## 数据更新与运行

使用 Code-local Python，无第三方 Python 依赖。资料更新独立于患者分析；运行期只读取固定摘要的本地快照，不对每个变异联网。输出目录必须不存在，失败残片不能作为成功输入。

```powershell
& local/tools/alphagenome/python/python.exe scripts/acmg-generalization/bs1_knowledge.py fetch --source gencc --output local/resources/acmg/<new-gencc-directory>
& local/tools/alphagenome/python/python.exe scripts/acmg-generalization/bs1_knowledge.py fetch --source orphadata --output local/resources/acmg/<new-orpha-directory>
& local/tools/alphagenome/python/python.exe scripts/acmg-generalization/bs1_knowledge.py compile --manifest local/resources/acmg/<input-manifest.json> --output local/resources/acmg/<new-compiled-directory>
```

每个来源限定 64 MiB、一次传输、30 秒单次读取超时和 1800 秒批量总期限，不自动重试。保存实际 URL、header、响应／解压后内容和 SHA。编译器不联网，严格核对输入原始字节摘要；GenCC 保留断言身份与版本，完整原 CSV 仍是事实原件。运行 bundle 限定 32 MiB，背景不超过 50,000 条，结构化记录不超过 20,000 条。

输入 manifest：`schemaVersion=bs1-knowledge-input-v1`、`version` 和 `sources[]`；每个来源含 `sourceId/kind/path/url/version/sha256`。相对路径相对 manifest；`url` 必须为 HTTPS。支持：

| kind | 输入与用途 |
| --- | --- |
| CLINGEN_JSON | 既有精确 HGNC/MONDO/MOI 关系快照，复用原完整来源 |
| GENCC_CSV | 官方 NEW CSV，`sgc_id + version_number` 唯一，保留全部来源断言；不按票数覆盖冲突 |
| ORPHA_XML | 官方流行病学 XML，保留统计类型、人群、验证状态和来源；不把发病率或病例数当患病率 |
| CSPEC_RAW | 含完整 `ld.CriteriaCode/Gene/RuleSet` 的官方原响应；只收入当前 Released。保留 BS1 已有专家范围，未结构化前阻止通用模型绕过 |
| CURATED_RECORDS | `bs1-knowledge-records-v1` 的 `records[]`；待采用或已采用的结构化知识 |
| EXPERT_CLAUSE_REVIEW | `bs1-expert-clause-review-v1`，绑定完整已发布CSpec对象的明确技术审阅与精确条款；由 `bs1_expert_batch.py` 编译 |
| QUALIFICATION_REVIEW | `bs1-qualification-input-v1`，固定源原文、精确范围、附加条件和例外的明确采用检查；由 `bs1_qualification.py` 绑定，未解决项不执行 |
| MONDO_SSSOM | 官方带版本SSSOM TSV；仅MONDO→Orphanet的 `skos:exactMatch` 联结流行病学候选，保存原行号/原行 |

输出：`bundle.json` 为运行快照；`epidemiology.json`、`specifications.json` 为完整事实；`parameter-drafts.json` 为 UNREVIEWED 参数草稿；`rejected-relationships.json` 记录未精确映射关系；`coverage.json` 分开统计关系、参数与政策覆盖。GenCC 提交的 ORPHA 原身份仅用于候选联结，不能自动证明病种范围完全相同。

`disease-mappings.json`保留精确本体映射；一对多映射不自动挑选，名称相似或closeMatch不联结。映射候选有来源也不等于其流行病学统计已经可用于当前疾病模型。

## 统一适用条件与采用接线（第三阶段）

### AF频率扩展（2026-10-09）

在原FAF95路径之外，新合同可指定 `metricId=gnomad.exomes.eligible_ancestry_max_af`。statistic字段必须恰为 `metricId/unit/confidence/ancestries/sourceTerm/interpretation/interpretationBasis/eligibleAn`；unit=fraction、confidence=POINT_ESTIMATE。AF解释为TARGET_ALT_AF，来源术语MAF只允许显式采用的TARGET_ALT_AF_NOT_ABOVE_HALF；含义尚未核定不得先填写通过。

`eligibleAn={operator:GT或GTE,decimal:十进制整数字符串}`，至少2000；祖源只允许现有六组。运行先筛AN再按精确AC/AN取最大，同值按代码排序；阈值亦交叉乘法比较。保留原AF小数，但不用其舍入值决定边界。未知资格／缺字段／数值冲突具体NE；低AN可明确排除，无合格人群不是NOT_MET。MAF解释对目标比例超过一半的记录停止，不取1−AF，不声称实现任意位点的全局MAF。

专家compile新增 `translation=REVIEWED_SINGLE_TARGET_AF_BOUND_V1`，沿单Strong阈值语法，另须 `populationStatistic` 和在完整条款中精确存在的 `eligibleAnExpression`（如`>2000`）；阈值、AN比较符/数值、来源术语和原文全部绑定。输出尚无qualification，必须再接QUALIFICATION_REVIEW；资料未知条件不可省略。不是看到MAF就自动改用AF。

MAX_CREDIBLE_AF记录可新增 `populationStatistic`，其祖源列表须与supportedAncestries精确一致，且要有qualification。未提供时仍走旧FAF95路径。AF与FAF条件不可混合，指标版本仅加入使用新AF的知识身份；历史28份完整输出已逐字节复验不变。

页面只读适配已支持新指标与筛选说明，损坏关联撤销成功展示。最小Java `Bs1FrequencyStatisticTest,Bs1KnowledgeBusinessTest`，Python `test_bs1_frequency_metric.py`；源条款可选回放属性为 `bs1.af.source-replay/source-report`，不给输入则明确跳过。只接受新输出路径。当前本轮未新增日常知识或更改配置，详细范围见[阶段记录](../../docs/execution/BS1_FREQUENCY_METRIC_2026-10-09.md)。

`QUALIFICATION_REVIEW` 复用原 `compile --manifest ... --output <新目录>`，不联网。顶层只接受 `schemaVersion/reviewer/reviewedAt/basis/entries`。每条entry只接受 `record/payloadSha256/sourceBindings/checks`；record须先具有绑定原内容的明确本地采用回执，提取器的UNREVIEWED草稿不能直接进入。模型增加限制时，新的原内容采用回执也必须覆盖限制，不能沿用修改前摘要。

`sourceBindings[]`只含 `sourceId/locator/contentSha256`。sourceId及record.sources的URL/版本/RAW SHA须与manifest相符；locator为固定JSON的RFC6901指针，或既有 `GNxxx/ld/...` 定位。编译时重读对应来源（每份至多64MiB），核对整文件RAW及locator所指完整JSON的 `varvis-json-c14n-v1` 摘要。两种摘要不能混用。其他类型来源须先有明确JSON事实表示，不能凭文字描述跳过定位。

`checks`须恰有五项，每项含 `status/basis/sourceIds`，引用本记录已绑定来源：

| 项 | 已核定状态／含义 |
| --- | --- |
| SOURCE_COMPLETENESS | SATISFIED；完整条款、相关规则与引用资料已足够支撑声明范围 |
| PARAMETER_SCOPE | 专家为PUBLISHED_THRESHOLD_NOT_RECALCULATED；模型为MODEL_PARAMETERS_MATCHED；参数定义／人群／统计分母及范围需匹配 |
| POPULATION_METRIC | SATISFIED；源规则的统计口径与本地可执行事实相同 |
| ADDITIONAL_CONDITIONS | SATISFIED；全部适用条件均有表达及可执行事实，或有依据地不要求 |
| EXCEPTIONS | SATISFIED；声明范围内例外集合完整，定位和来源明确 |

每项也可为UNRESOLVED；理由和来源必须保留。编译器不会判断科学解释是否正确，文字 `SATISFIED` 也不是科学批准。运行期通过 `Bs1KnowledgeQualification` 再核内容和结构，未解决项返回具体类别NE；输出完整 `knowledgeRecord.qualification` 可追溯其余检查。不得将模型尚未支持写成数据库没有资料。

输出 `qualification` 绑定原payload（除review/qualification外所有内容）、scope、来源、检查、作者和完整 `priorReview`。外层review归属改为本次合同作者，覆盖含qualification的新内容；原回执完整保存，不暗示原审核者签过新合同。原强度／expertSpecificationCheck保持。新合同版本进入运行身份；不带新合同的历史记录按原校验，新增modelRestrictions则必须有新合同。

限定通用模型 `modelRestrictions` 只支持 `variantTypes/transcripts/commonConditions`：变异限SNV/SMALL_INSERTION/SMALL_DELETION；转录本为原accession/version语法；conditions用原AN/AC/FAF有限条件语法，原选择器和评价器实际执行。未知键／测序深度／年龄等条件拒绝，不默认忽略。精确例外还要求同HGNC/MONDO、VERIFIED_ALLELE、规范化GRCh38 queryAllele、NOT_APPLICABLE及绑定的mappingSource。专家记录直接保留完整原政策，不接受另套modelRestrictions；两处例外集合必须相同。

本轮只验证22条既有记录迁移候选，保持28可选择背景；未替换日常快照、新增采用0。最终候选在 `bs1-knowledge-qualification-20261009/candidate-02`，不要用同目录compiled-01中间版本；见[阶段记录](../../docs/execution/BS1_KNOWLEDGE_QUALIFICATION_2026-10-09.md)。

新增最小测试：Python `test_bs1_qualification.py`；Java `Bs1KnowledgeQualificationTest,Bs1KnowledgeBusinessTest`。固定迁移候选检查通过系统属性 `bs1.qualification.bundle/sha/prior/report` 显式指定新bundle、新RAW SHA、旧bundle和未存在的报告路径；未传参数会跳过该可选检查，不能称已验证实际知识。业务全JSON输出属性 `bs1.knowledge.business-output` 指向新目录，分别生成legacy/qualified；禁止覆盖旧验收原件。

## 批量参数文字提取（第二阶段）

`compile` 在原字段盘点之后调用 `bs1_parameter_text.extract`，新增三份准备文件。只处理固定Released CSpec的完整BS1条款；没有按基因或疾病编码的分支，也不联网。

- `parameter-text-proposals.json`：原文、源身份、完整locator、精确范围、提取版本与稳定clauseId；每个词语有Unicode code-point `[start,end)`位置和原文子串，不能按UTF-8字节或JavaScript UTF-16索引直接切片。
- `numeric`保存精确分子／分母、原始单位、POINT／BOUND／RANGE／APPROXIMATE；区间保留书写端点及规范化界限，开闭未知时不推定。语法不支持、否定、备选、多值或运算都明确标记。没有从文本自动决定参数科学资格。
- 每字段只在全部同名词语均解析为同一明确点值时给 `candidateValue`。`completeExplicitPointSet`表示同一条款四项点值结构完整；不跨条款拼接，不代表年龄、人群、统计分母、遗传方式、专家条件或例外已被采用。
- `parameter-text-backgrounds.json`：全部关系背景到提案的精确HGNC/MONDO/MOI引用，找不到记为 `NO_MATCH_IN_CURRENT_EXTRACTION`，不宣称所有文献都没有资料。
- `parameter-text-coverage.json`：文字候选／完整草稿／关联背景分开统计。`newScientificAdoptions/newExecutableBackgrounds`仍为0；旧 `parameter-coverage.json`继续统计第一阶段结构化事实，不能混用分母。

所有提案 `UNREVIEWED/modelExecutable=false/scientificallyAdopted=false`；不会生成运行record或review。使用现有 `CURATED_RECORDS` 采用前，仍须核对完整条款、专家优先、参数定义/分母、人口/年龄/统计类型、模型强度及例外；提取器不能伪造无适用专家规范。区间/近似仅保存，没有加入当前单点模型；叙述型数字、反向标签或复杂数学未解析则保留源文和原因，不填默认值。

新增测试：`python -m unittest discover -s scripts/acmg-generalization -p test_bs1_parameter_text.py`；连同原三组Python为66项。最终当前准备输出 `local/resources/acmg/bs1-parameter-extraction-20261009/text-02/`，15份完整文字草稿关联12背景，新增可执行0；细节及原文自验见[阶段报告](../../docs/execution/BS1_PARAMETER_EXTRACTION_2026-10-09.md)。`compiled-01`的完整规范／bundle可作为固定输入复用，不能把中间提取结果当最终版本或自动绑定日常配置。

## 批量参数识别（第一阶段）

`compile` 现通过 `bs1_parameters.py` 对全部关系背景输出字段级核定，不再把四个字段统一标记为缺失。此步骤不选择参数、不生成采用回执、不改运行政策；可用原manifest在新目录执行，不能覆盖旧输出或自动切换日常配置。

- `parameter-observations.json`：每个来源事实的稳定ID、完整源身份／locator、原始数值／单位／统计类型／人群、规范化点值或区间以及问题代码。Orphadata逐记录保留原Source引用；CSpec保留精确范围内的BS1全文定位与参数词语线索，不从自由文本自动采用数值。
- `parameter-drafts.json`：`schemaVersion=bs1-parameter-assessment-v1`，每个背景含四项`parameters`，引用事实ID，保留全部映射／范围和阻断原因。`missingParameters`现在仅列`SOURCE_GAP`，不能再将其长度解释为全部不可执行原因；其余见`blockedParameters`、`blockingReasons`。所有草稿仍UNREVIEWED、`selectedValue=null`、`modelExecutable=false`。
- `parameter-coverage.json`：逐字段互斥状态、数值候选覆盖、原因分布、已加载来源类型、完整数值候选集与新增采用数。数字以背景为分母；多原因可重叠，不能相加为总覆盖率。`coverage.json`仍保留原兼容统计。

| 字段状态 | 含义 |
| --- | --- |
| CANDIDATE_AVAILABLE | 有结构可处理、来源基本条件满足的候选；不等于科学采用或完整模型可用 |
| REVIEW_REQUIRED | 已有文本、来源资格／范围待核或多个人群需选择；不是资料不存在 |
| CONFLICT | 同一来源疾病／人群／统计口径的数值不同，需核对时间和研究范围；不判定哪份研究错误 |
| IDENTITY_UNRESOLVED | 缺精确流行病学映射，不能断言疾病资料不存在 |
| SOFTWARE_UNSUPPORTED | 已有区间或统计类型，当前单点模型／解析器尚不支持 |
| INVALID_SOURCE_DATA | 当前输入数值格式／范围不合法，不静默修正 |
| SOURCE_GAP | 仅在本次固定输入及当前提取范围内，没有找到对应的合适估计；不等于全世界没有文献 |

Orphadata的ValMoy按每十万人换算；Class only的0.0为占位，保留原区间及开闭界，不取中点。数值与所报分级不符进入待核。病例数、年发病率不作为患病率；出生／终生患病率与现有只支持POINT_PREVALENCE的模型限制分开记录，不称这些资料科学上无用。定义依据：[官方说明V3，2026年4月，第23—25页](https://www.orphadata.com/docs/OrphadataScienceDataDescription.pdf)。

CSpec仅检索已载入Released原件中BS1条款的外显率／患病率／贡献比例文字，按RuleSet精确HGNC/MONDO/MOI关联；未知MOI显式待核。没有搜索全部论文、附表或自动解析数字。已结构化MAX_CREDIBLE_AF记录可进入字段盘点，但本模块不验证或赋予其科学采用资格，运行期仍由原知识校验器负责。

本轮全量识别结果与限制见[第一阶段记录](../../docs/execution/BS1_PARAMETER_INVENTORY_2026-10-09.md)。最小测试为 `python -m unittest discover -s scripts/acmg-generalization -p 'test_bs1*.py'`；无需为了仅改变准备材料而重复上游或页面验收。

## 批量专家条款编译

### 性染色体专家阈值准备（A10）

新增 `translation=REVIEWED_SEX_CHROMOSOME_THRESHOLD_V1`，只编译已发布、明确审阅的专家阈值；`bs1_parameters` 的通用最大可信频率模型仍仅支持 AD/AR。保留原单阈值或有界区间、百分比、严格比较符、完整条款与来源摘要；不会根据一般 X 连锁标签计算或选择 XLD/XLR。

`sexChromosomeScope` 要求 `assemblies/contigs/regions/regionCatalogSha256/requiredDataset/sourceId/sourceLocator`。构建限 GRCh37/GRCh38，区域限 PAR/NON_PAR，数据集要求 EXOMES/JOINT 显式保存。区域摘要绑定共享 S0 表。`populationStatistic` 要求 `metricId/unit/confidence/ancestries/karyotypes/eligibleAn/sourceTerm/interpretation/interpretationBasis/sourceId/locator`，metric 为 `gnomad.exomes.sex_ancestry_max_af/faf95` 或独立的 `gnomad.joint.sex_ancestry_max_af/faf95`，分别绑定 AF 点估计或 FAF95 下界，并必须与 requiredDataset 一致；`karyotypes` 仅 ALL/XX/XY。PAR 不允许要求原数据不提供的 XX/XY FAF。AN 的来源要求和本地工程下限分别说明，不冒充原始条款。

若原 CSpec 仅写 `X-linked inheritance`，需要 `reviewedModeMappings[]` 的独立精确 MOI 来源。每项含 `sourceMode/targetMode/hgncId/mondoId/sourceId/locator/contentSha256/interpretationBasis`；源节点需同时匹配基因、疾病、XLD/XLR 模式及已建立的关系等级。manifest 固定原始文件 SHA，内容摘要固定 locator 节点；同原件换 sourceId 不能当独立来源。除既有 `CLINGEN_JSON`，`GENCC_SUBMISSION_HTML` 会直接重读 GenCC 单个 submission 原 HTML，解析明确 HGNC/MONDO/HPO MOI/等级与 accession，并保留每字段 UTF8 字节范围及摘要；`/0` 指确定性原件投影，不是手写 JSON 原件。泛 XL 与 hemizygous 不产生细分，精确来源或 CSpec 明确 qualifier 冲突独立保留 UNRESOLVED。

准备 helper 的 `dataset_readiness/local_readiness` 只检查资料集边界，不评分。JOINT/EXOMES 不符返回 `BS1_SEX_RESOURCE_DATASET_MISMATCH`，匹配但资源不可读返回 `BS1_RESOURCE_EXECUTION_UNAVAILABLE`；满足也只是 `ELIGIBLE_FOR_RUNTIME_VALIDATION`，仍需后续完整 qualification 与真实原记录核验。普通无性染色体审阅的编译输出保持。

性染色体审阅可显式声明 `supportingSourceBindings[]`（仅 `sourceId/locator/contentSha256/interpretationBasis`），把指导原件一并加入 record.sources 和 qualification 重读链。`PINNED_PDF_REFERENCE` 严格验证 PDF 原字节并投影 `/reference` 的 RAW SHA/长度/媒体类型；`PINNED_TEXT_REFERENCE` 另保留 `/text` 原文。页码/章节及科学解释由审阅者明确提供，二进制身份验证不冒称 PDF 自动语义审核。缺源、错 locator、变更节点或原字节都拒绝。

固定 GN027 v2.1 的真实原件可用于限定回放：其 BS1 声明 Grpmax Filtering Allele Frequency；[ClinGen gnomAD 指导 v2.0](https://www.clinicalgenome.org/site/assets/files/9445/august_2024_communication_to_clingen_vceps_from_clingen_svi_vcep_review_committee_1.pdf) 要求联合口径。一般 X 连锁 MOI 缺独立细分映射、联合资料与本地 exomes 不符，都应准确保留不可采用原因；不据此构造真实 MET。新增独立测试为 `test_bs1_expert_sex_chromosome.py`。

审阅文件顶层要求 `reviewer/reviewedAt/basis/dataScopePolicyId` 和 `specifications[]`。每条包括 `specificationId/version/entitySha256/decision/instruction/fraction/operator/expectedBackgrounds/crossRuleReview/exceptionReview/scopeReview`；EXOMES 使用 `BS1_LOCAL_EXOMES_V1` / `ADOPT_LOCAL_EXOMES`，JOINT 性染色体使用 `BS1_JOINT_SEX_V1` / `ADOPT_JOINT_SEX`，qualification 的 priorReview 保留同一范围。entitySha256是完整官方实体的规范化摘要，manifest仍绑定源文件RAW摘要。二者职责不同，不能把摘要一致当作自动科学批准。

当前翻译器仅支持已明确审阅的单STRONG、简单FAF阈值条款，核对百分比与十进制换算、比较符、完整适用范围及全部AD/AR模式。新增源限制、额外适用强度或未知模式明确拒绝，不能正则提取数字后丢掉其余条件。优先在同一数据语法增加合格记录；确需新条件时扩展一次共享语法和针对性测试，不按基因新增评价器。

`translation=REVIEWED_SINGLE_FAF_BOUND_V1` 另要求 `clauseText/thresholdExpression/statisticReview/additionalConditionsReview`，完整文本与源精确一致，数值表达式原样存在且单位/比较符相符。只支持明确审阅的单Strong FAF条款，不由正则自动决定科学适用。源整体必须Released；旧式strength无status字段只有明确 `statusBasis=RELEASED_ENTITY_LEGACY_STRENGTH_STATUS_ABSENT` 才接受，显式未批准仍拒绝。缺失modesOfInheritance时仅允许精确AD/AR的preferredModeOfInheritance，空数组不回退。

本地技术采用不能写成外部独立审核或临床批准。两批22条知识记录保留完整源范围、交叉证据解释与例外检查；规则仅在患者明确选中对应疾病/遗传方式后应用。专家关系元数据单列 `BS1_EXPERT_SPECIFICATION_SCOPE`，`classificationIsGeneValidity=false`；原基因有效性评级和冲突不会被覆盖，MANE映射必须精确。

当前本机快照：`local/resources/acmg/bs1-cross-domain-knowledge-20261009/compiled-01/bundle.json`；manifest保留两批审阅来源。原17个RASopathy背景保持，新增GATM/VHL/IDUA/GFAP/GALT五背景，合并旧政策后选择READY为28。真实验证与未采用条款见[当前阶段报告](../../docs/execution/BS1_CROSS_DOMAIN_KNOWLEDGE_BATCH_2026-10-09.md)和[上一批记录](../../docs/execution/BS1_EXECUTABLE_KNOWLEDGE_BATCH_2026-10-09.md)。运行仍离线查本地人口数据，不为每个变异重新抓取疾病资料。

## 一份结构化记录

共同字段：`recordId/version/kind/scope/sources/exceptionSet/review`。`scope` 要求精确 HGNC、MONDO 和一个 AD/AR 遗传方式。`sources[]` 的 sourceId、URL、版本和原始 SHA 必须与 bundle 来源表一致，并给出非空 locator。完整原件保留在私有资源目录，不上传患者信息。

- `EXPERT_POLICY`：包含完整原 `expertPolicy` 和 Released 状态；使用既有有限政策语法，保留阈值、强度、共用条件、例外和转录本要求。不能只摘出一个数字忽略其他条款。
- `MAX_CREDIBLE_AF`：包含 `model` 和 `supportedAncestries`。model 必须有 `prevalence/penetrance/maximumGeneContribution/maximumAlleleContribution`，每个参数含十进制字符串 `value`、`unit=fraction`、`sourceId` 和 `locator`；患病率另需 `statistic=POINT_PREVALENCE`。还需人口描述 `population` 与 `applicability=MENDELIAN_RARE_DISEASE`。没有任何参数默认值。
- `KNOWLEDGE_GAP`：保留明确缺项，不能被解释成 NOT_MET。

采用信息 review 绑定除 review 以外全部内容的 `varvis-json-c14n-v1` 摘要（复用 `RuntimeEvidenceV2Adapter.canonicalSha256`，不是文件 RAW SHA）。需 `status=REVIEWED_FOR_LOCAL_USE`、`contentSha256/reviewer/reviewedAt/basis` 和 `dataScopePolicyId=BS1_LOCAL_EXOMES_V1`。模型还需明确本地采用强度 STRONG/SUPPORTING，以及已核对无适用专家专规；例外集合须覆盖声明范围。这些是服务端知识采用记录，不能用客户端确认、自动抓取或自填审核文字代替真实依据。修改参数后旧采用摘要立即失效。

无临床批准自动化。测试中的 `SYNTHETIC_TEST_NOT_CLINICAL_APPROVAL` 记录只用于工程验证，绝不放入日常知识库。

## 计算与优先关系

采用 Whiffin 等公开模型：设 K 为患病率，f 为外显率，g 为基因最大贡献，a 为等位基因最大贡献。

- AD：`K × g × a / (2 × f)`。
- AR：`sqrt(K × g / f) × a`。

参考 [Whiffin 等原论文](https://www.nature.com/articles/gim201726)、[作者计算器](https://cardiodb.org/allelefrequencyapp/) 和[作者公式实现](https://github.com/jamesware/alleleFrequencyApp/blob/master/server.R)。独立实现公式，没有复制其 R 程序。计算本身只返回 CALCULATED_NOT_ADOPTED；是否适合 BS1、可用强度、祖源和疾病范围仍须有依据。当前只支持上述明确 AD/AR 模型，性染色体、线粒体、复杂遗传、区间参数和其他统计类型保留缺项。

阈值用 40 位有效数字向上界舍入，比较为 FAF95 严格大于阈值。数学舍入不会把刚好相等变成满足；这不等于解决患病率等参数的统计不确定性。后者须通过参数资格和适用范围处理。

既有专家政策（包括有未解决条件的专家范围）优先；发现新专家范围但尚未结构化时，不能退回通用模型。冲突关系、身份不唯一、参数不足、例外未解决或质量不足分别返回具体理由。

人口事实继续使用本地 gnomAD exomes 4.1、原生 PASS、同祖源 FAF95/AC/AN/AF 和 AN≥2000 保护。不把本地数据升级为 joint，不新增远程人口请求，不将未知频率设为零。每个患者背景仍需显式选择；公开疾病关联不是患者确诊。

## 接入与最小验证

配置 `variant-analysis.acmg-business.bs1-knowledge-file` 和配对 `bs1-knowledge-sha256`（bundle RAW SHA），须已启用本地 BA1＋BS1。服务启动时加载固定快照，其身份进入推荐与执行 profile；更新知识后精确复用身份改变。只有已保存的旧结果按原版本读取。日常配置变更不意味着常驻服务已重启。

直接入口：`Bs1KnowledgeCatalog` → `Bs1Runtime.localExomes` → 原 `Bs1LocalExomes/Bs1CriterionEvaluator`；新增关系经 `PatientDiseaseContextBindingResolver.withBs1Knowledge`，仍受既有身份、来源及显式选择门禁。

聚焦测试：`Bs1KnowledgeCatalogTest`、`Bs1KnowledgeBusinessTest`、`AcmgBusinessConfigTest`、`PatientDiseaseContextBindingResolverTest`；导入器 `python -m unittest discover -s scripts/acmg-generalization -p test_bs1_knowledge.py`。前端 `bs1-knowledge-component-check.mjs` 使用测试实际保存的合成 JSON，不能冒充真实患者或真实下载。

专家编译器补充测试：`python -m unittest discover -s scripts/acmg-generalization -p test_bs1_expert_batch.py`。Maven可选真实快照参数为 `bs1.knowledge.test-file/test-sha/test-report`；额外 `bs1.knowledge.rasopathy-controls=true` 仅验证MONDO:0021060的冻结RASopathy阈值。跨领域第二批使用 `bs1.knowledge.expected-file` 指向预先冻结5条源预期、`bs1.knowledge.boundary-report` 指定独立输出位置；不能从待测bundle生成期望值或覆盖旧任务原件。

资料依据：[GenCC 官方下载](https://thegencc.org/download)、[ClinGen 下载](https://search.clinicalgenome.org/kb/downloads)、[Orphadata 流行病学](https://sciences.orphadata.com/epidemiology/)、[CSpec Registry](https://cspec.genome.network/)。GenCC 按 CC0、Orphadata 按 CC BY 4.0 使用；保留原始出处、版本、许可声明和访问记录，不能从公共关系资料推定所有疾病参数已获得。
