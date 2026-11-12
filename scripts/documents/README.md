# 历史教学材料与生成脚本

`build_acmg_bilingual_handbook.py` 生成日期基线为 **2026-08-31** 的 ACMG/AMP 双语学习手册。
本次只整理位置和输出参数，未更新或重新核验手册中的科学内容，不能据此认定其代表最新规范。

## 原件归档

2026-09-14 经用户授权，将仓库中的 PDF 原件移至相邻知识库的现有 `sources/` 结构：

- [归档 PDF](../../../Knowledge/sources/2026-09-14_varvis_handbook/ACMG_AMP_2015_28_Criteria_Bilingual_Study_Handbook_2026-08-31.pdf)
- [归档说明](../../../Knowledge/sources/2026-09-14_varvis_handbook/README.md)

以上链接依赖本地 `Code/` 与 `Knowledge/` 相邻布局；单独克隆代码仓库时可从 Git 历史恢复原件。

| 项目 | 历史位置与身份 |
| --- | --- |
| PDF 原路径 | `output/pdf/ACMG_AMP_2015_28_Criteria_Bilingual_Study_Handbook_2026-08-31.pdf` |
| 可恢复原件的提交 | `0d0075fad4d578c100ed47260bb9b71ab6d8776b` |
| PDF SHA-256 | `62d29b51f593472c13fb3c317cb693168278afa1459e1dc42d3b3d13cc390656` |
| 生成脚本原路径 | `tmp/pdfs/build_acmg_bilingual_handbook.py`，原先未纳入 Git |
| 原生成脚本 SHA-256 | `5c7fa98753720c3da40ae19bfcbd6ddf0b8cb1f08b46e84fec23a38c23a31faa` |

归档前后对原件进行了 SHA-256 比对。原生成脚本备份及旧 Hail 日志的本机路径记录在私人交接归档的
`2026-09-14-repository-tidy-batch1/manifest.json` 中；本仓库不提交机器绝对路径。

## 重新生成

需要可用的 Python、ReportLab，以及脚本现有字体选择逻辑依赖的 Windows 字体。
本次未安装依赖、未改动字体或排版，也未重新生成归档 PDF。

```powershell
python scripts/documents/build_acmg_bilingual_handbook.py --output "<目标目录>/handbook-review.pdf"
```

`--output` 必填，输出父目录会自动创建。目标文件已存在时脚本拒绝覆盖，应选择新的文件名。
生成结果供复核使用，不能假定重新生成的文件与历史 PDF 具有相同字节或校验值。
