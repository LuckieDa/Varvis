# Varvis

Varvis 是一个面向罕见病变异研究的工作台，包含染色体可视化、VCF 候选变异分析和 ACMG 证据评价的前后端实现。

本仓库提供 2026-11-12 的当前源码快照，保留源码、构建配置、测试和程序需要的共用脚本。不包含开发机器上的数据库、患者文件、分析历史、大型参考数据、模型、密钥或已安装的依赖。

## 主要功能

- 染色体总览与局部区域展示，查看变异、基因和疾病关联信息。
- VCF 上传、样本选择、候选变异筛选与排序。
- ACMG 证据准备、单条或多条证据分析、证据详情与来源展示。
- 变异证据得分排序及五级分类展示。
- 分析任务控制、历史结果读取和结果导出。

## 代码结构

```text
varvis-client-pro/    React / Umi 前端
varvis-server/        Spring Boot / MyBatis 后端
scripts/             后端资料处理、资源准备和工作区辅助脚本
```

染色体展示页面的主要代码位于：

- 前端：[`varvis-client-pro/src/pages/varvis/ChromosomeVisualization/`](varvis-client-pro/src/pages/varvis/ChromosomeVisualization/)
- 后端接口：[`varvis-server/src/main/java/org/nbscn/varvis/`](varvis-server/src/main/java/org/nbscn/varvis/)
- 数据查询映射：[`varvis-server/src/main/resources/mapper/varvis/`](varvis-server/src/main/resources/mapper/varvis/)

## 环境要求

| 部分 | 要求 |
| --- | --- |
| 前端 | Node.js 20+、npm 9+ |
| 后端 | JDK 17+、Maven 3.8+ |
| 业务数据 | MySQL 数据库及相应表和数据 |
| 完整 VCF 分析 | 另行配置 Exomiser、参考数据及相关工具；Exomiser 需要 Java 21 |

## 获取代码

```bash
git clone https://github.com/LuckieDa/Varvis.git
cd Varvis
```

## 启动后端

先准备有权使用的 Varvis 数据库连接和对应的表、数据。仓库中的 SQL 文件提供部分分析模块表结构，不包含完整的染色体知识库数据。

以下示例在 Windows PowerShell 中，从仓库根目录执行。将示例值替换为自己的配置：

```powershell
& .\scripts\migration\Initialize-VarvisWorkspace.ps1
$env:VARVIS_DB_URL = 'jdbc:mysql://localhost:3306/Varvis?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai'
$env:VARVIS_DB_USERNAME = 'your_database_user'
$env:VARVIS_DB_PASSWORD = 'your_database_password'
cd varvis-server
mvn "-Dmaven.repo.local=../local/cache/maven" spring-boot:run
```

后端默认端口为 `8086`。配置入口是 [`application.yml`](varvis-server/src/main/resources/application.yml)。也可参考 [`database-local.properties.example`](varvis-server/config/database-local.properties.example)，将实际连接信息写入被 Git 忽略的本地配置文件。

## 启动前端

在另一个 PowerShell 终端中，从仓库根目录执行：

```powershell
& .\scripts\migration\Initialize-VarvisWorkspace.ps1
cd varvis-client-pro
npm ci
npm start
```

按终端显示的地址打开页面，默认地址为：

```text
http://localhost:8000/#/varvis/chromosome-visualization
```

前端默认连接 `http://localhost:8086`。后端地址不同时，在运行 `npm start` 前设置 `VARVIS_API_BASE`。

前端生产构建：

```bash
cd varvis-client-pro
npm run build
```

## 数据与分析资源

只查看或修改界面代码，可以先启动前端；染色体数据查询需要后端及已配置的知识库。完整 VCF 和 ACMG 分析还依赖本地参考序列、注释数据、证据规则、文献资料和对应工具，不能只凭源码恢复开发机器上的完整分析环境。

资源路径、模型服务和外部接口通过环境变量或本地配置接入。需要的资源请按实际使用的功能准备；不要把数据库密码、API 密钥或患者资料提交到仓库。

## 测试

前端检查脚本位于 `varvis-client-pro/scripts/`，后端测试位于 `varvis-server/src/test/`。部分集成和科学资源测试依赖本地资料、已归档规范或验收样本，这些内容没有随本次源码共享上传。

本次上传是当前开发代码的共享快照，不表示所有可选分析资源已随仓库提供，也不表示每个持续开发中的分支均已完成最终验证。
