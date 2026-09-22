# jev-suite · 总开发方案

> 四项目共享一副骨架。核心决策：**抽共享内核 jev-kit，不做四个 fork**。依据：四个应用的 Jev 客户端、清洗、门控、eval 框架完全相同，差异只在适配器/schema/输出（各 ~30% 代码）。

## 1. 仓库结构（monorepo）

```
jev-suite/
├── jev-kit/                    # 共享内核（Maven module，四应用依赖）
│   ├── client/    JevClient / TypeSafeJevClient / MockJevClient（含 429/重试/降级）
│   ├── state/     ExternalStringSanitizer（白名单清洗）
│   ├── schema/    QuestionSchema（含 422 三坑修复）
│   ├── gate/      通用门控（noul≥pHigh→✓ / ≤pLow→✗ / 中间→人审，阈值外置 yml）
│   └── eval/      EvalRunner 基类 / EvalReport / mock 硬失败防护
├── jev-proof/     # P1 口播验收（本目录已含 docs）
├── jev-fit/       # P2 岗位差距
├── jev-fidelity/  # P3 编辑保真
├── jev-rental/    # P4 房源确认
└── docs/          # 本文件 + 总纲
```

jev-kit 从 jev-guard 对应包抽取泛化：GateLogic 从"充提门控"泛化为"Noul 三态门控 + Choice 直读 + Score 序数映射"；ScreeningService 不进 kit（各应用主链路不同），DecisionLog 泛化进 kit。

## 2. 里程碑（每项目统一四段，复用 jev-guard 纪律）

| 段 | 内容 | 验收 |
|---|---|---|
| M1 | 骨架 + 适配器 + mock 跑通 | 无 key clone 即跑 demo |
| M2 | 真机冒烟 + 发现固化 | docs/05-findings（本项目的实测坑） |
| M3 | 标注样本 + 三列对照 eval | 校准报告（免责声明顶格、mock 硬失败） |
| M4 | README（含局限性）+ 发布 | push + awesome PR |

工期（含返工，串行）：jev-proof 2.5 天 → jev-fit 2 天 → jev-fidelity 2 天 → jev-rental 1.5 天。后三个项目复用 kit，主要写适配器+schema+eval 样本。

## 3. 关键技术决策（四项目通用）

1. **Jev 分诊、代码裁判**：每个项目都有硬规则层和"失败降级到最保守桶"原则
2. **阈值全外置** yml，eval 推荐值人工确认手动改，不自动回灌
3. **反注入**：所有外部文本（字幕/简历/JD/房源描述/双文本）过白名单清洗；每项目 eval 含 10 条注入回归
4. **校准即卖点**：每项目 n=50-60 样本、三列对照、免责声明顶格
5. **不承诺延迟数字**：README 放实测值
6. **结果可导出**：报告 PDF/JSON 即审计凭证（这是 B 侧故事的地基）

## 4. 各项目差异化要点

| 项目 | 最难适配器 | Jev 问题数/次 | 基线对照 |
|---|---|---|---|
| jev-proof | YouTube 字幕抓取+分段（yt-dlp） | 要点数+2 | 关键词匹配 |
| jev-fit | 简历 PDF 解析+证据条目化 | ≤20（粗筛后） | Jobscan 式关键词匹配 |
| jev-fidelity | 双文本事实对齐 | 事实单元数 | difflib/余弦相似度 |
| jev-rental | 声明提取（最轻） | 声明数+1 | 关键词规则 |

## 5. 发布节奏（引流叙事线）

每个项目一篇小红书：P1「品牌方用它验收口播，观众用它跳广告（攻守两端）」→ P2「我用 AI 分析自己简历和 JD 的差距」→ P3「编辑有没有把稿子改丢东西」→ P4「租房前让 AI 帮你列该问什么」。awesome PR 每项目照投 top3。
