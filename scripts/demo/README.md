# 本机组会演示

当前入口：项目原有页面 `http://localhost:8000/#/varvis/chromosome-visualization`，
进入 **VCF Analysis → Open Analysis Lab**。PP3/BP4 现在位于原有候选结果区。
较早的 `/varvis/group-demo` 地址保留兼容，复用同一个规则面板。

原页面使用真实上传接口、Exomiser G0、VEP 和既有 PP3/BP4 evidence-v2 业务入口。
教学 VCF 是 60 条公开等位基因组成的人工集合，基因型、病例及疾病声明是 synthetic。
不输入 HPO，不进行最终 ACMG 分类，也不更改正式候选顺序。

## 现场操作

1. 在 Analysis Lab 点击“下载 ACMG 教学 VCF”，再用 **Choose VCF** 选择下载的文件。
   Input build 选 **GRCh38**，分析模式 **WES**，Sample ID 填 **VARVIS_GROUP_DEMO**，
   HPO 和 excluded HPO 均留空，不开启 trio；点击 **Analyze**。
   Case VCF 是染色体叠加入口，排名使用 Analysis Lab 的上传入口。
2. 等待状态变为 READY，原页面显示正式候选排名。
   本机首次 G0 实跑约 6 分半，主要是 Exomiser 数据加载；请留出等待时间。
3. 在 **4. Result overview** 点击 **Top-20 PP3 / BP4（教学 VCF）**，
   面板读取当前任务的正式前 20 名，独立于排名表的分页和筛选。
   说明并勾选模拟病例声明，点击“评价 Top-20 · PP3 / BP4”。
4. 展示 PP3 满足 12 条、BP4 满足 1 条。点击第 10 名 GJB2：AM 0.1547，
   BP4 条件为 ≤ 0.169，精确 allele/转录本/蛋白及资源检查通过，强度 Supporting。
   可再打开第 2 名展示 PP3，并说明第 1 名两条均未满足。
5. 点击“查看依据”展开每条规则采用的分数、阈值、前置检查、强度和阻断原因，
   下载规则结果或单条证据；“返回候选排名”回到原面板。
6. 保存页面中的 Analysis ID。整页刷新后，可在 Analysis Lab 的
   “已有分析任务 ID”输入它并点击“读取已有分析”，再打开规则面板读取已保存结果。
   此过程不重新排名或评价，也不用重复确认病例声明。

本机已验收任务 ID 为 `c2744815-8198-4e23-bf56-fba78a15ce7d`，可通过上述原页面
读回，用作现场等待排名时的真实已保存结果。该功能读取后端存储，不内嵌规则结果。

数值来自 2026-09-06 这次实际验收，资源、版本及配置变化后须重新验证。
“排名靠前”“PP3 满足”“最终致病分类”是不同概念；本演示只覆盖前两者。
两个方向都未满足不等于良性；来源不足时保留“无法评价”及原因。

## 准备与启动

已准备本机运行目录时，由用户分别打开两个终端；无需重新生成材料。
第一个终端在仓库根目录执行：

```powershell
& scripts/demo/start-group-demo.ps1 -RuntimeRoot $env:VARVIS_DEMO_ROOT `
  -ResourceRoot $env:VARVIS_RESOURCE_ROOT -ForegroundBackend
```

等待 `Started VarvisServerApplication`，第二个终端执行：

```powershell
cd varvis-client-pro
npm start
```

`-ForegroundBackend` 只在当前终端运行项目本身的 Spring Boot 后端；不会启动前端、
打开浏览器或创建后台会话。两边分别按 Ctrl+C 停止。默认包含本地离线编译，
以便加载最新源代码，不需要重新安装依赖。

教学 H2 只含分析任务表，不含染色体知识库。如果首页提示知识库数据不可用，
点击该提示中的 **Open Analysis Lab**，查看分析预检并进行 VCF 流程。
此处不会伪造染色体概览或复制远程知识库。

以下为首次准备或复现旧后台启动方式的说明：

只使用既有本地资源和工具，不下载资源，不访问共享服务器。资源根目录通过
`VARVIS_RESOURCE_ROOT` 指定；默认资源位于 `local/resources`，演示运行目录放在
`local/runtime` 下并与源码分开。
在仓库根目录执行（先填写本机环境变量）：

```powershell
& $env:VARVIS_PYTHON -B scripts/demo/prepare_group_meeting_demo.py `
  --resource-root $env:VARVIS_RESOURCE_ROOT `
  --binding-root $env:VARVIS_BINDING_ROOT --output-dir $env:VARVIS_DEMO_ROOT
& scripts/demo/start-group-demo.ps1 -RuntimeRoot $env:VARVIS_DEMO_ROOT `
  -ResourceRoot $env:VARVIS_RESOURCE_ROOT -Maven $env:VARVIS_MAVEN
```

已有本轮验证后的编译结果时，启动加 `-SkipBuild`。启动后等待本地服务就绪再打开页面。
脚本强制两个 JDBC 地址均指向演示目录内的 H2，覆盖原有远程数据库配置；不会停止占用端口的其他进程。
`session.json` 保存本轮进程身份及日志位置。停止时执行：

```powershell
& scripts/demo/stop-group-demo.ps1 -RuntimeRoot $env:VARVIS_DEMO_ROOT
```

停止脚本核验 PID、启动时间、命令和进程树；分析子进程仍运行时拒绝停止后端。
VCF、数据库、分析产物及日志保留。不要删除演示目录：可信执行来源恢复仍依赖原始产物。

Exomiser 自带的参考数据库用于排名；演示 H2 用于保存上传任务、候选和规则结果。
这是两个不同用途的数据库。普通启动配置和默认 ACMG 执行开关不变；
上述命令显式加载教学配置启用 PP3/BP4。

## 使用已经连接 Varvis 数据库的普通后端

若使用原有后端启动方式，教学 H2 启动脚本中的 ACMG/VEP 开关不会自动生效。
先使用已验证的教学资源配置补齐普通后端的显式规则配置：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo/configure-existing-backend-acmg.ps1 -RuntimeRoot <教学运行目录> -ValidateOnly
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/demo/configure-existing-backend-acmg.ps1 -RuntimeRoot <教学运行目录>
```

该脚本只更新 Git 忽略的本地分析配置中 9 项 ACMG/VEP 设置，并备份原配置。
数据库、存储及 Exomiser 排名参数不变；不会下载资源、执行数据库迁移或自动启动服务。
重启本机后端后，新建教学 VCF 分析，再执行 Top-20 评价。
此前 VEP 关闭时生成的候选没有完整转录本证明；保留旧任务及阻断结果，不修改其来源快照。
`ACMG_BUSINESS_EXECUTION_DISABLED` 表示规则没有执行，不能解释为 PP3/BP4 未满足。

## 独立验收

`capture_group_demo.py` 从本机 API 保存 job、Top-20 和最新成功运行。
停止演示后端后，用现有 Java 21、`target/classes` 及
`target/demo-runtime-classpath.txt` 构成 classpath，执行：

```text
java -cp <existing-runtime-classpath> scripts/demo/ExportGroupDemoAudit.java <runtime-root> <analysis-id> <run-id>
python -B scripts/demo/verify_group_demo.py --runtime-root <runtime-root> --resource-root <resource-root> --summary-output <summary.json>
```

导出工具只读打开已存在的本地 H2；私有快照留在演示目录，不提交。
校验复用 E1 schema、身份及 bundle validator，以及原有 REF 证明校验。
重启后执行 `capture_group_demo.py --verify-readback`，比较 API 内容并重复提交原请求，
确认同一运行返回且未创建新运行。Python 使用项目既有含 jsonschema 的环境。
