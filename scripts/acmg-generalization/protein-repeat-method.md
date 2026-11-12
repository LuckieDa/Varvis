# TRUST 本地蛋白重复方法验证

这是方法验证工具，未接入 PM4/BP3 正式业务。当前已测配置有短重复漏检、原生 Repeat 漏检和堆内存失败，**不能作为唯一非重复区域判定器**。详见[阶段总结](../../docs/execution/PM4_BP3_REPEAT_METHOD_VALIDATION_2026-10-13.md)。

## 输入和依赖

- Python 3.12 标准库、本地 JDK 21；不安装 pip 包。
- 作者原始 TRUST 1.0 包，不改编译类/源码/矩阵，不运行网络请求或全参考扫描。
- 使用独立的 panel/profile JSON 与精确 SHA；本次冻结例子位于私有 `pm4-bp3-repeat-method-20261013/dev-01/`，不把真实源/第三方工具二进制纳入仓库。
- 工具清单须先固定并核来源，调用 `--tool-manifest-sha256` 传入该固定值；不是运行时随意为已变化的工具生成新身份。原下载/提取清单绑定包和每个 class。
- 一条 protein ID 对应一个完整标准 20AA 序列及其 SHA；明确禁止截短、`*`、未知残基、重复 ID、路径字符和大小写碰撞。
- profile 固定参数与预算见源码；更改配置须新一轮事前声明和验证，不能静默套用旧结果。

## 执行

`protein_repeat_method.py` 接受以下必需参数：

| 参数 | 内容 |
| --- | --- |
| `--panel` | 固定 cases；含 id/sequence/sequenceSha256，及 profileSha256 |
| `--profile` | 方法身份、矩阵身份、固定参数与预算 |
| `--tool` | 含 trust.tgz、Align/ 的本地工具根目录 |
| `--tool-manifest` | 原提取清单，每文件 path/bytes/sha256 |
| `--tool-manifest-sha256` | 已固定清单的精确 SHA-256 |
| `--java` | 已有完整 JDK 的 java.exe 绝对路径 |
| `--output` | 尚不存在的全新任务目录，禁止覆盖 |

结果包括每条输入、实际命令、stdout/stderr、进程回收与完整解析事实。完成且无命中才是 `NO_REPEAT_DETECTED`；工具失败不产生这种状态。总体退出码 2 表示至少一项未完成或身份复核失败；已完成原件仍保存，不能把批次退出 2 改称全通过。

`evaluate_protein_repeat_panel.py --panel ... --run .../results.json --output .../evaluation.json`
离线统计同一固定对照，只读原始结果，不重新运行科学工具。输出禁止覆盖。它是描述性分析，不产生科学批准。

## 语义

- 坐标一基闭区间；对齐去掉 gap 并大写后，必须逐字等于输入对应片段。
- 每个重复单元独立保留；不把首末单元中间所有位置都计为命中。
- 默认输出 `UNREVIEWED`，非重复资格、无功能资格、临床采用全部 false。
- TRUST 的内部重复不一定是连续的短串联重复；上游显著性不是 ACMG 强度。
- 上游含随机统计，无可设置 seed；精确原件可复读，不能承诺重新计算逐字节相同。
- 无自动持久事实缓存、Java provider 或旧 bundle 桥接；需要这些能力时应在方法采用范围确定之后复用原项目模块。

## 测试

用项目现有 Python，设 `PYTHONDONTWRITEBYTECODE=1`，并将 `TEMP/TMP` 指到本 worktree 的 `local/temp/`，然后：

```text
python -m unittest discover -s scripts/acmg-generalization -p "test_*protein_repeat*.py" -v
```

测试不会下载工具或运行真实蛋白，仅有三个很短的本任务 Python 子进程验证退出、超时和输出上限。产品后端没有改变，不要求由这些测试触发 Maven 或页面回归。
