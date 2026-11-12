# PS1/PM5 支线页面启动与人工验收

本入口只供 `feature/ps1-pm5` 独立 worktree 的验收。它使用现有页面及正式
`/variant-analyses` 接口；构建通过、HTTP 通过和人工页面通过应分别记录。

**验收完成（2026-09-09 20:41，Asia/Shanghai）：用户已完成页面首评、同声明重复、
刷新读回，并反馈结果、来源链接和缺口说明全部正常。** 页面 runId 为
`09903ba1-5e7f-42ee-a53e-e12f2faec935`，PM5 MET/Moderate、PS1 NOT_MET；首次、
重复与刷新后的完整保存 JSON 一致，重复及刷新新增调用全为 0。详细技术记录见
[支线验收记录](../../docs/execution/PS1_PM5_BRANCH_ACCEPTANCE_2026-09-09.md)。
本次未覆盖 PS1 Strong 的页面显示；其历史真实后端正向记录仍有效。
下方操作步骤保留供复现，不要求再次执行。需要结束预览时，在用户启动前端的终端
按 Ctrl+C 即可；本次执行者没有停止前后端或操作其他支线，三线不自动合并。

**最新进度（2026-09-09 20:20，Asia/Shanghai）：用户已启动前端，静态入口与代理
HTTP 核验通过。当前跳过下方启动/构建/恢复段，直接进入“人工检查”。**
前端观察 PID 23140、后端 PID 20572；首页和 12 个 JS/CSS 内容核对通过，代理保存
响应与直接及留存结果完整一致，20 项调用基线/增量均为 0。保持当前前端终端运行。
先读旧结果，再页面首评；首评完成后报告实际 runId 并保持所有页面选择不变，
待执行者记录首评计数再重复；最后才刷新读回。以下旧启动阻断说明保留历史含义，
用户手动启动不代表工具审批已解除。

## 2026-09-09 已准备的本轮实例

真实 PM5 MET/Moderate、保存、网络 HTTP 与独立 JVM 重启复用已通过；页面仍待人工
验收。后端实现 SHA 为 `2eab07feb1670d5c61d29e000acee56a1f7a9f5f`，验收客户端修正
提交为 `495b525`，后续记录提交不改变运行代码。前端已成功构建，无需因记录更新重建。
本轮 8216 前端预览的启动被自动审批返回 `blocked by policy` 拒绝，未创建进程，
因此静态页面及代理 HTTP 检查也未覆盖。以下启动由用户在自己的终端执行。

后续入口复核发现原 PID 18508 已退出、两个端口均空闲；已用原 launcher、原
instanceRoot 和私有 H2 恢复后端。2026-09-09 19:57（Asia/Shanghai）核对为
**18217 / PID 20572**，HTTP 身份与原 manifest 一致，保存结果完整相同，20 项调用
计数均为 0。进程号只代表记录时状态，不能据此直接停止或重启。

**当前先执行下面这一段启动前端，跳过后文的重新构建和后端启动段。**
8216 尚无实例，自动审批阻断未解除；本段交给用户在本地 PowerShell 自行运行，
并不表示代理已启动。运行前进入本支线 worktree；若端口占用或身份不符，停止并
保留错误，不换端口、不终止其他进程。启动成功后告知执行者，先完成只读静态/代理
HTTP 核验，再进行下面的人工操作。

```powershell
$ErrorActionPreference = 'Stop'
$repo = (& git rev-parse --show-toplevel).Trim()
if ((& git branch --show-current).Trim() -ne 'feature/ps1-pm5') { throw '请进入 PS1/PM5 worktree。' }
$instanceRoot = Join-Path $repo 'varvis-server/target/ps1-pm5-network/instance-196cc2ae-deb3-44e5-962b-fdc05902c57f'
$instanceManifest = Get-Content -LiteralPath (Join-Path $instanceRoot 'instance-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$instance = Invoke-RestMethod 'http://127.0.0.1:18217/__varvis_ps1pm5_instance' -TimeoutSec 10
foreach ($field in 'instanceId','sourceHead','configurationSha256','branch','database','transport','port') {
    if ($instance.$field -ne $instanceManifest.$field) { throw "后端身份不符：$field" }
}
if ($instance.profileId -ne 'CLINVAR_BRAF_RASOPATHY_PS1_PM5' -or $instance.profileVersion -ne '2.0.0') { throw '后端科学 profile 不符。' }
node (Join-Path $repo 'varvis-client-pro/scripts/ps1-pm5-preview.mjs') $instance.instanceId $instance.sourceHead $instance.profileId
```

保持该终端运行；**Ctrl+C 只停止这个前端预览**。无需为页面验收停止后端；如后续
要停止后端，先重新读取 HTTP 身份、核对实际 PID 的命令行和原实例参数文件，再
处理该自有实例，不能使用本指南中的历史 PID 直接终止进程。

如果本实例已退出，先确认 18217 空闲，再按后文重启原实例步骤操作。
最新构建由 `latest-build.txt` 自动定位。
实际已保存任务为 `3c66c610-7ed6-4eab-baf6-ea6650e15373`，
runId 为 `8bc35332-4067-4dd3-8faa-7b65100a55e9`，可在原 Analysis Lab 重开读回。
若重新上传相同公开 VCF，选择 **WES、GRCh38、STANDARD、public-snv，不添加 HPO**，
再核对实际任务复用提示；页面生成的新声明是否与网络客户端相同须以实际响应为准，
不能预先保证跨不同声明仍是同一个 runId。

## 仅源码或产物改变时构建

在本 worktree 的 `varvis-client-pro` 中运行：

```powershell
./scripts/Build-Ps1Pm5Frontend.ps1
```

脚本复用机器上已有的 D 盘依赖缓存，不安装依赖。各依赖包通过本地 junction
读取，`node_modules/.cache`、`src/.umi-production` 和临时目录仍在本 worktree。
每次构建输出到 `../varvis-server/target/ps1-pm5-expansion/frontend/build-<id>/`，
其中 `build.log`、`build-manifest.json` 保存结果、构建时 HEAD、源码和产物 SHA256。
`latest-build.txt` 指向本次成功构建，失败构建不会替换它。默认构建配置不受影响。

## 仅原后端退出时恢复

先用仓库 `scripts/ps1_pm5/Start-Ps1Pm5Network.ps1` 启动私有 H2 后端
**127.0.0.1:18217**。以下命令可从本 worktree 内执行；资源根环境变量指向已配置的
本机资源目录，JDK、classpath 和私有配置须由本轮运行准备提供，不能替换成原服务的
数据库配置。集中编译完成后再启动，不得用原有 8086 服务替代。

```powershell
$repo = (& git rev-parse --show-toplevel).Trim()
$private = Join-Path $repo 'varvis-server/target/ps1-pm5-private'
$varvisResourceRoot = $env:VARVIS_RESOURCE_ROOT
if (!$varvisResourceRoot) { throw '请先设置本机已核验的 VARVIS_RESOURCE_ROOT。' }
$backendArgs = @{
    Java = Join-Path $varvisResourceRoot 'Tool/exomiser/java/temurin-21.0.11+10-x64-windows/bin/java.exe'
    ClassPathFile = Join-Path $private 'network-classpath.txt'
    Config = Join-Path $private 'runtime.properties'
    ResourceRoot = $varvisResourceRoot
}
$instanceRoot = Join-Path $repo 'varvis-server/target/ps1-pm5-network/instance-196cc2ae-deb3-44e5-962b-fdc05902c57f'
$instanceManifest = Get-Content -LiteralPath (Join-Path $instanceRoot 'instance-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$launch = & (Join-Path $repo 'scripts/ps1_pm5/Start-Ps1Pm5Network.ps1') @backendArgs -Restart -InstanceRoot $instanceRoot -SourceHead $instanceManifest.sourceHead | ConvertFrom-Json
```

启动回执只表示已启动，需等待回执所列日志确认就绪；失败时先查看该实例日志，
不要重复创建后端。就绪后执行本指南最上方的身份核对与前端启动段即可。
`profileId` 由真实 HTTP 返回，不能从磁盘 manifest 推断；不需要用户手写 SCV、
内部 hash 或来源证明。

第四个可选参数可指定本 worktree 的已成功构建目录。启动器核对构建产物和当前
前端源码 SHA256，再核对后端分支、显式提供的版本、实例、profile、端口及私有
H2/真实网络 HTTP 标识。任何身份缺失或不符均停止；8216 被占用时停止，不换端口、
不终止原进程。构建时 HEAD 与后端实现 SHA 会分别保留，不能说成同一构建版本。

`-SourceHead` 必须属于当前分支历史，且 launcher 会核对运行源文件与该 SHA 无差异。
只有文档提交时继续使用原实现 SHA；实际代码或资源变化时不能沿用旧验收身份。

浏览器手动打开：

```text
http://127.0.0.1:8216/#/varvis/chromosome-visualization
```

`http://127.0.0.1:8216/__varvis_ps1pm5_frontend` 提供双方版本核对信息。
启动记录 `preview-<time>.json` 写入该构建目录。窗口中的 Ctrl+C 只停止这个前端
预览进程；不要操作其他工作区的服务。此入口不会自动打开浏览器或提交评价。

## 人工检查

1. 打开上述 8216 页面，点击 **Open Analysis Lab**；在“已有分析任务 ID（可选）”
   输入 `3c66c610-7ed6-4eab-baf6-ea6650e15373`，点击 **读取已有分析**。
   优先复用现存正式任务。隔离后端没有完整知识库 Controller，总览可能提示
   knowledge-base 加载失败；只要 Open Analysis Lab 可用，无需连接其他服务补齐。
   如果该按钮也无法使用，记录具体失败位置，交由执行者做必要局部修复。
   只有确需新分析时才上传仓库 `scripts/ps1_pm5/public-missense-control.vcf`，选择合成 Sample ID
   **public-snv**。它只有公开位点 **GRCh38 chr7:140801550 G>C（BRAF T241R）**，
   人工设置杂合 `0/1`，不是患者文件。选择 **GRCh38、常规分析（STANDARD）**，
   保留原筛选条件，实际分析后检查正式候选及遗传方式。
2. 打开统一证据面板、读取输入选项。确认此时没有触发评价。按本轮正式候选
   选择 **Top-1、BRAF / RASopathy / AD**，确认候选确实符合该规范关系。
3. 核对推荐转录本 **ENST00000646891.2** 及“VCEP 规范适用关系”。AM 版本不匹配时，页面应说明 AM
   兼容性尚未建立；可用的 PS1/PM5 转录本不能被显示成 AM 已兼容。换到其他
   疾病关系后不能继续借用这项例外。
4. 先在“已保存的运行记录”选择 `8bc35332-4067-4dd3-8faa-7b65100a55e9`，点击
   **读取保存结果**，核对现存结论。再按页面完成必要确认并显式评价，不填写 SCV、hash
   或内部证据对象。页面初次声明可能与旧 Python 客户端不同，合法新 runId 可接受；
   不修改声明来强制复用旧运行。分别查看 PS1 与 PM5 的实际状态和强度。详情应包含
   参考来源、蛋白关系、政策、剪接条件、独立性及依赖。出现 NOT_MET 或
   NOT_EVALUATED 时保留原因；不能把检索到的参考关系全算作满足证据。
   本轮后端已验证、页面待核对的预期为 **PM5 MET / Moderate、PS1 NOT_MET**，SpliceAI 目标最大分数
   **0.00**、参考最大分数 **0.04**；这是固定输入的验收预期，不能据此提前宣称
   页面已通过。真实 HTTP 结论见本轮支线验收记录；AM 不兼容的边界继续保留，PP3/BP4 不借此视为满足。
   参考为 **T241K / SCV001424734.1 / VCV000044829.13**。RNA 文献详情应说明
   有限检索、影响未知，不将低分或未发现报告说成 RNA 正常。PP3/BP4 因 AM
   TRANSCRIPT_PROTEIN_MISMATCH 未评价；BA1 因 NO_RECORD 未评价，不能显示为低频或良性。
5. 查看保存结果，刷新后核对 runId 和完整结论；相同请求重复提交应复用。
   新增引擎、资源查询、模型和 evaluator 次数由后端实际计数证明，不能仅靠
   页面响应速度推断。先记录本次页面首评的 runId，再对**同一份页面声明**重复评价，
   比较完整响应与 runId；不要把刷新后重新生成的不同声明当作相同请求。
   首评若产生新运行，记录声明差异和真实新增调用，不自动判失败。
   本轮不需要重做已通过的 JVM 重启及模型验收。
6. 保存人工操作时间、双方版本、analysisId/runId、可观察结论和失败边界。
   后端已经通过的项目可以引用对应记录；未经实际人工操作，不标记页面通过。

PS1/PM5 卡片按照有效 profile 或保存 manifest 显示；保留基线原有规则。
本支线不宣称已与尚未合并的 PM2 完成联合兼容，也不产生最终临床分类。

### 启动后由执行者核对的只读项目

保留静态 `/` 及构建 manifest 内 JS/CSS 的实际 200 响应和内容哈希，读取
`http://127.0.0.1:8216/__varvis_ps1pm5_frontend` 核对双方身份。经 8216 GET：

```text
/variant-analyses/3c66c610-7ed6-4eab-baf6-ea6650e15373/acmg/runs/8bc35332-4067-4dd3-8faa-7b65100a55e9
```

与 18217 直接 GET 及留存 `http-corrected/saved-business.json` 做完整 JSON 对比，
不能只比较 PM5 文案。检查前后读取 `http://127.0.0.1:18217/__varvis_ps1pm5_calls`，
证明没有新增引擎、模型、资源或评价调用。该入口同样用于人工操作前、首评后、
同声明重复后的计数留存；读取输入选项可能产生身份查询，须与评价/模型调用区分。
这些只读检查已于上述时间通过；后续若实例或源码变化，应重新核对受影响项目，
不能把本地文件哈希核对当作 HTTP 通过。

### 人工反馈记录

请记录：操作时间/时区；前端 buildId 与源码 SHA、后端 instance/sourceHead；
analysisId；页面首次及重复 runId；打开详情是否触发评价；PM5/PS1 强度、参考、
剪接分数、RNA 和 AM/BA1 缺口是否准确；刷新读回是否一致；首评/重复的调用增量；
未通过项及可观察错误。可下载两次“本次已保存结果 JSON”供完整比较。
现有私有数据库只保证上述 PM5 运行，未将历史 PS1 Strong 运行复制到此库；
仅在正式保存列表实际存在旧阳性时才核对其 Strong 显示并登记，否则标记本次未覆盖。
