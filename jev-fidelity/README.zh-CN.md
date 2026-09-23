# jev-fidelity

**原文与编辑稿之间的保真检查 —— Jev（TypeSafe System One）逐事实判定 保留 / 同义改写 / 语义偏移 / 丢失，确定性代码守住出口闸门。**

[English README](README.md)

粘贴原文和编辑稿，得到**逐事实**的保真报告，两侧都有锚点定位。定位是固定且诚实的：*我们不判断原文是否为真 —— 我们只判断编辑是否改变了它。*

## 为什么是这个架构

- **Jev 判定，代码把门。** 每个原文侧事实单元调用一次 Jev，问两个问题 —— Noul（"这究竟是不是可核查的事实？"）+ Choice（保留 / 同义 / 偏移 / 丢失）。置信度低于 0.70 的判定降级为 `REVIEW`，不直接出货。55 个校准样本（110 个事实标签）上：**92 个判了，错 1 个，17 个按设计弃权。**
- **没有候选，就没有判定。** 关键词对齐找不到编辑侧候选时，该事实标记为 `SUSPECTED_LOST` —— 永远不会是 `LOST` —— 且不调用模型。对齐错误绝不允许级联成判定。
- **相似度基线抓不住重点 —— 实测证明。** 数字翻转（34% → 60%）和否定翻转（"未批准" → "批准"）的关键词重合度约 0.9。相似度基线只能抓到 20% 的缺陷；门控管线抓 100%，精度 0.975。
- **观点会被过滤，这是有代价的 —— 如实记录。** `is_fact` 过滤可能吞掉观点句的删除事件（真实数据上踩到过，留在遗留问题清单里）。
- **降级到人工复核，绝不降级为放行。** 模型不可达 → `REVIEW` → `NEEDS_HUMAN_REVIEW`。

## 架构

```
原文 + 编辑稿
    │
    ▼
TextSplitter ── 中英文分句（小数点/缩写安全）
    │  （任一侧为空 → UNPARSEABLE，不调模型）
    ▼
对齐 ── 关键词 + 中文二元组重合度，每个事实取 top-3 编辑侧候选（纯代码）
    │  零候选 → SUSPECTED_LOST（不调模型）
    ▼
Jev ── 逐事实：Noul is_fact + Choice fidelity
    │   编辑侧新增句 → Noul needs_source（成本上限 5 条）
    ▼
置信度闸门 ── <0.70 → REVIEW
    ▼
整体判定 ── 任一 DRIFT/LOST → NOT_FAITHFUL · 任一 REVIEW/SUSPECTED_LOST → NEEDS_HUMAN_REVIEW
```

## 快速开始

需要 JDK 17+ 和 Maven。

```bash
git clone https://github.com/klauswg/jev-suite && cd jev-suite
mvn -pl jev-fidelity -am package
java -jar jev-fidelity/target/jev-fidelity-0.1.0.jar   # 绑定 127.0.0.1:8083

# 另开终端 —— 无需 API key，mock 模式从 fixture 应答：
curl -X POST http://127.0.0.1:8083/v1/check -H "Content-Type: application/json" -d '{
  "originalText": "Revenue grew 40 percent in 2024. The company hired 200 engineers.",
  "editedText": "Revenue grew 60 percent in 2024. The company hired 200 engineers."
}'
```

## 真实模式

```bash
cp .env.example .env   # TYPESAFE_API_KEY=...
java -jar jev-fidelity/target/jev-fidelity-0.1.0.jar
```

## 校准（M3，2026-09-23）

55 样本 / 110 个事实标签：30 条来自**真实 Wikipedia 修订 diff**（Bitcoin、Ethereum、Artificial intelligence、Elon Musk）+ 60 条构造（数字翻转、否定翻转、实体/限定词偏移、丢失）+ 20 条注入。严格标注口径：新增或改变实质细节 = 偏移；格式/同义词不算。

| 列 | 规则 | 准确率 | 精度 | 召回 | 弃权 |
|---|---|---|---|---|---|
| 相似度基线 | 关键词重合 < 0.5 → 缺陷 | 0.706 | 1.000 | 0.200 | 0 |
| Jev 裸判定 | 原始 choice | 0.927 | 0.833 | 1.000 | 0 |
| 组合（出货） | conf ≥ 0.70 闸门 | **0.989**（91/92） | **0.975** | **1.000** | 17（15%） |

注入：**0/20 判定被翻转**。口径发现（宽松 vs 严格标注让 REAL 准确率从 0.55 变 0.94）和两套原始数据都在仓库里：[docs/calibration-report.md](docs/calibration-report.md) + [eval/results/](eval/results/)。

复现（mock 模式按设计硬失败；`--allow-mock` 会给输出加水印）：

```bash
java -Dloader.main=com.jevsuite.fidelity.eval.EvalRunner \
  -cp jev-fidelity/target/jev-fidelity-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --from 0 --to 55
```

## 局限性

1. **保真 ≠ 事实核查。** 原文本身的错误按设计不在范围内。
2. **依赖口径约定。** 什么算"偏移"是编辑约定；本工具使用严格口径并明说。信任输出前请先对齐你的口径。
3. **真实样本仅来自英文 Wikipedia 修订**，单人标注；中文编辑流程、新闻通稿、自媒体改写未覆盖。
4. **观点过滤可能吞掉删除事件**（被删引语因非事实被排除）。
5. **长文档（>5,000 字符）未验证** —— 请先切分。
6. **Jev 置信度轮间波动 ±0.1**，边缘判定可能翻面。闸门就是为这个存在的。
7. **不是法律合规证明。**

## License

MIT
