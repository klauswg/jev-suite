# jev-proof

**创作者赞助口播验收 —— Jev（TypeSafe System One）判定视频是否兑现了 brief；确定性代码保留否决权。**

[English README](README.md)

品牌方给出一份逐条可核验的 brief（"提到 30 天无理由退款"、"前 30 秒内披露赞助"），创作者交回视频。今天的做法是人从头到尾看一遍手工打勾。jev-proof 回答的问题是：*给定 brief 和视频字幕，哪些要点被兑现、赞助是否披露、哪里需要返工？* 语义判定来自 Jev；**证据选取、门控阈值、最终状态留在 Java** —— 可单测、可审计、可调参。

## 为什么是这个架构

- **Jev 判定，代码把关。** 每个 brief 要点一个 Noul 问题（"字幕证据明确兑现该要点"），state 只给相关字幕窗口。门控（pHigh=0.80 / pLow=0.20 / cHigh=0.70）把灰区判定转成 `HUMAN_REVIEW` 而不是掷硬币。60 样本校准集上：**Jev 裸判定 80/80 全对；门控管线 68/68 全对 + 12 个设计内弃权** —— 零错误放行。
- **无证据不判过。** 关键词重叠粗筛为每个要点挑候选字幕窗口；找不到就直接 FAIL —— 模型永远不会被要求判定它看不见的证据，也没机会编造证据。
- **披露一票否决。** 赞助披露 `missing` 或 `misplaced` 直接 `NEEDS_FIX`，不管要点完成得多好。
- **关键词匹配不够 —— 这是测出来的，不是断言。** 同一批标签下关键词基线准确率 61%、31 个假阳性（话题邻近全被误判为兑现）。校准报告就在仓库里。
- **降级到 REVIEW，永不降级到 PASS。** 上游超时/5xx/429 → 该要点标 `REVIEW`。宁可烦一个人工，也不放过一个坏检查。

## 架构

```
视频 URL
    │
    ▼
SubtitleFetcher ── yt-dlp（外部进程）→ VTT → ASR 重复行去重 → 合并 ~45s 窗口
    │  （无字幕/视频不可用 → UNVERIFIABLE，不调模型）
    ▼
roughMatch ── 关键词重叠为每个要点挑 top-5 候选窗口（纯代码，控成本）
    │
    ▼
ProofService ── 逐要点：Noul 问题 + 消毒后的证据窗口
    │          ── 元问题：披露（Choice）+ 质量（Score），只看前 90 秒
    │           失败 → 该要点 REVIEW（降级）
    ▼
NoulGate ── 逐要点 PASS / FAIL / REVIEW
    ▼
overall ── 披露一票否决 → 要点汇总 → PASS / NEEDS_FIX / HUMAN_REVIEW
```

## 快速开始

需要 JDK 17+、Maven，以及 PATH 里的 [yt-dlp](https://github.com/yt-dlp/yt-dlp)（或设置 `YTDLP_PATH`）。

```bash
git clone https://github.com/klauswg/jev-suite && cd jev-suite
mvn -pl jev-proof -am package
java -jar jev-proof/target/jev-proof-0.1.0.jar   # 绑定 127.0.0.1:8081

# 另开终端 —— 无需 API key，mock 模式返回确定性 fixture 答案：
curl -X POST http://127.0.0.1:8081/v1/accept -H "Content-Type: application/json" -d '{
  "brandName": "Acme", "productName": "Acme Blender",
  "points": [{"id":"p1","text":"Introduces the Acme Blender by name","mustHave":true}],
  "disclosureRequirement": "Say \"this video is sponsored\" within the first 30 seconds",
  "videoUrl": "https://www.youtube.com/watch?v=iG9CE55wbtY"
}'
```

## 真实模式

```bash
cp .env.example .env   # TYPESAFE_API_KEY=..., YTDLP_PATH=...
java -jar jev-proof/target/jev-proof-0.1.0.jar
```

## 校准结果（M3，2026-09-23）

60 条标注样本：10 条真实公开视频（字幕快照，标签对照转写人工核实）+ 40 条合成（改写兑现 / 话题邻近 / 披露变体 / 部分兑现从严）+ 10 条注入攻击。要点级共 80 个标签：

| 列 | 口径 | 准确率 | 精确率 | 召回率 | 弃权 |
|---|---|---|---|---|---|
| 关键词基线 | 要点词（>3字符）出现在转写中即判兑现 | 0.613 | 0.603 | 1.000 | 0 |
| Jev 裸判定 | noul ≥ 0.5 | **1.000** | **1.000** | **1.000** | 0 |
| 组合（产品口径） | 门控；REVIEW = 弃权 | **1.000**（68/68） | **1.000** | **1.000** | 12（15%） |

披露列：55/58（3 个错误全是埋在 90 秒窗口之后的后置披露——missing 和 misplaced 都映射 NEEDS_FIX，产品结论不变）。注入攻击：**0/10 判定被翻转**。总计 82k input tokens，0 降级。完整数据：[docs/calibration-report.md](docs/calibration-report.md) + [eval/results/](eval/results/)。

复现（mock 模式默认硬失败；`--allow-mock` 输出自动加水印）：

```bash
java -Dloader.main=com.jevsuite.proof.eval.EvalRunner \
  -cp jev-proof/target/jev-proof-0.1.0.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --from 0 --to 60
```

## 局限性

1. **yt-dlp 是硬外部依赖。** YouTube timedtext 直抓已死（poToken 封锁，2026-09-22 实测）。yt-dlp 哪天被 YouTube 变更打破，字幕入口就跟着断。
2. **只判文本。** 评估对象是字幕转写：画面展示、口播语气、ASR 字幕错误都不在判定范围。
3. **披露检测只看前 90 秒。** 刻意后置的披露会被抓住（判 missing → NEEDS_FIX），但无法与完全缺失区分。
4. **校准集小且 2/3 为合成**（60 样本、真实标签单一标注者、仅英语演讲类视频）。100% 的数字描述的是这个集合，不是普遍准确率声明——弃权门控正是为分布外输入兜底的。
5. **demo 级 API。** 绑定 127.0.0.1、内存报告存储、无鉴权——生产使用请放在自己的网关后面。
6. **验收是人的决定。** 输出状态是复核辅助信号，不是法律/合规裁决。

## License

MIT
