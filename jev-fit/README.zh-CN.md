# jev-fit

**简历 × JD 的校准差距分析 —— Jev（TypeSafe System One）判定你的证据是否满足每条要求；确定性代码把门。**

[English README](README.md)

贴入简历和 JD，得到一份**逐要求判定**的差距报告：每条 JD 要求被简历证据满足的程度（带概率）、差距类型归因、哪里值得改写——每个判定都锚定你的简历原文行号。**不出百分制分数，不虚构经历。**

## 为什么是这个架构

- **Jev 判定，代码把门。** 每条要求一次 Jev 调用两个问题——Noul（证据是否满足）+ Choice（差距类型）。门控（pHigh=0.75 / pLow=0.30 / cHigh=0.70）把灰区转成 `UNCERTAIN` 交给人。60 样本校准集上：**门控管线 115/115 全对 + 5 个设计内弃权。**
- **证据召回第一——先测出来，再修掉。** 第 1 轮评估发现纯关键词粗筛让模型拿不到证据（recall 0.713：简历写 RocketMQ，JD 写 message queue，关键词零重叠）。修复：重叠排序前 6 + 不足补齐。Recall 升到 1.000，token 成本仅 +4%。失败数据就在仓库里。
- **关键词匹配是被我们打败的基线，不是我们的方法。** 同一批标签下 Jobscan 式关键词基线：77% 准确率、21 漏报、7 误报。这个差距就是产品本身。
- **伦理红线写在代码里。** `hard_skill_missing` 只给学习提示，永不生成改写建议；只有 `phrasing_mismatch` 且 noul ≥ 0.6 才标记可改写——而且只标锚点行，不代笔。审批权在你。
- **降级到人工复核，永不降级到通过。** 模型不可达 → `UNCERTAIN` → `NEEDS_HUMAN_REVIEW`。

## 架构

```
简历文本 + JD 文本
    │
    ▼
ResumeParser ── 按行 → 证据条目（行号锚点）
JdParser     ── 条目 → 要求（信号词过滤，must/nice-to-have 区分，上限 20 条）
    │  （JD 无法解析 → 直接报错，不调模型）
    ▼
证据选取 ── 关键词重叠 top-6 + 不足补齐（纯代码，控成本）
    │
    ▼
Jev ── 逐要求：Noul 满足？+ Choice 差距类型
    │   元问题：整体匹配 5 档（Score，不出百分制）
    ▼
NoulGate ── SATISFIED / GAP / UNCERTAIN
    ▼
汇总 ── STRONG_MATCH / APPLY_WITH_GAPS / BLOCKED_BY_MUST_HAVE / NEEDS_HUMAN_REVIEW
```

## 快速开始

需要 JDK 17+ 和 Maven。

```bash
git clone https://github.com/klauswg/jev-suite && cd jev-suite
mvn -pl jev-fit -am package
java -jar jev-fit/target/jev-fit-0.1.0.jar   # 绑定 127.0.0.1:8082

# 另开终端 —— 无需 API key，mock 模式返回确定性 fixture 答案：
curl -X POST http://127.0.0.1:8082/v1/analyze -H "Content-Type: application/json" -d '{
  "resumeText": "Backend engineer\n- 6 years of Java development with Spring Cloud\n- Built payment clearing services",
  "jdText": "Senior Backend Engineer\nRequirements:\n- 5+ years of backend development experience in Java\n- Experience with payment or clearing systems is required\n- Experience with React is preferred"
}'
```

## 真实模式

```bash
cp .env.example .env   # TYPESAFE_API_KEY=...
java -jar jev-fit/target/jev-fit-0.1.0.jar
```

## 校准结果（M3，2026-09-23）

60 条构造样本（30 岗位对 / 20 边界案例 / 10 注入攻击），120 个要求级标签：

| 列 | 口径 | 准确率 | 精确率 | 召回率 | 弃权 |
|---|---|---|---|---|---|
| 关键词基线 | 要求词出现在简历即判满足 | 0.767 | 0.894 | 0.738 | 0 |
| Jev 裸判定 | noul ≥ 0.5 | 0.983 | 1.000 | 0.975 | 0 |
| 组合（产品口径） | 门控；UNCERTAIN = 弃权 | **1.000**（115/115） | **1.000** | **1.000** | 5（4.2%） |

注入攻击：**0/20 判定被翻转**。81k input tokens，0 降级，单样本约 931ms。完整数据：[docs/calibration-report.md](docs/calibration-report.md) + [eval/results/](eval/results/)——包括促成证据选取修复的第 1 轮失败数据。

复现（mock 模式默认硬失败；`--allow-mock` 输出自动加水印）：

```bash
java -Dloader.main=com.jevsuite.fit.eval.EvalRunner \
  -cp jev-fit/target/jev-fit-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --from 0 --to 60
```

## 局限性

1. **一致率 ≠ 面试通过率。** ATS 行为因公司/系统/配置而异。本报告衡量构造样本上的判定质量，不预测录用结果。
2. **MVP 只接文本。** PDF 双栏/图片简历解析在 V2。
3. **JD 解析是启发式。** 非常规格式的 JD 可能漏条目。
4. **证据补齐有上限。** 超长简历（50+ 条目）仍可能漏关键证据（top-6 上限）。
5. **样本全部手工构造**（常见岗位原型，单一标注者）。真实投递场景表现未验证。
6. **不代笔，是有意的。** 改写建议只标你的证据锚点。想要一键塞关键词的工具，这个恰好不是。
7. **不构成职业咨询建议。**

## License

MIT
