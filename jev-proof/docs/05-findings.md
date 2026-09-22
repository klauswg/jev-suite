# jev-proof · M2 真机实测发现

> 实测日期：2026-09-23。全部来自真机链路（真实 YouTube 视频 + 真实 Jev key），非文档转述。

## 1. YouTube 字幕抓取：直抓 timedtext 已死（2024 起）

- watch 页 HTML 里的 `captionTracks` 仍在（本视频 65 条轨），但**带签名的 baseUrl 直抓返回 0 字节**——加 `fmt=srv3/vtt/json3`、带 watch 页 cookie 均无效，YouTube 对 timedtext 强制 poToken。
- Innertube `youtubei/v1/player`（ANDROID 客户端）直接 400。
- **结论：不要自己追 YouTube 的反爬移动靶，用 yt-dlp**（其存在意义就是跟进这些变动）。`--skip-download --write-subs --write-auto-subs --sub-langs en,en-US,en-GB,en-orig --sub-format vtt` 一次成功。
- 注意 `--sub-langs "en.*"` 会把 en-af 等翻译轨也列进去并在翻译轨上吃 429；语言列表要精确。
- 配置项 `jev.ytdlp.path`，默认 PATH 查找。这是 PRD「无外部二进制依赖」的实测推翻，已记录。

## 2. VTT 解析要点

- 自动字幕（ASR）相邻 cue 大量重复同一句话 → 连续相同文本去重。
- cue 文本含 `<b>` 等内联标签与 HTML 实体 → 解析时剥离。

## 3. Jev 判定行为（TED 演讲 iG9CE55wbtY，3 要点 + 披露 + 质量）

| 要点 | 人工事实 | Jev noul | 门控 | 解读 |
|---|---|---|---|---|
| creativity = literacy | 演讲确实说了（~110s 处） | 0.13 | FAIL | **粗筛召回漏了**：关键词重叠 top3 没把含原句的窗口送进 state，Jev 只能按弱证据判 |
| 小孩画上帝故事 | 确实有（~215s 起） | 0.40 | REVIEW | 证据段送进来了但 state 截断 300 字符没含「上帝」部分 → 部分给分，保守 REVIEW |
| 折扣码 ACME20 | 不存在 | 0.03 | FAIL | 正确 |
| 披露 | 无赞助 | missing | NEEDS_FIX 一票否决 | 正确 |
| 质量 | 非赞助内容 | 1（off_brief） | — | 合理 |

**两个待 M3 校准量化的问题**：
1. 粗筛召回：关键词重叠 top3 对「表述与原句不完全同词」的要点会漏 → 候选：topN 3→5、证据截断 300→600、或按要点关键词在全文定位窗口而非打分排序
2. 门控行为符合设计：证据不足一律 REVIEW/FAIL，绝不默认通过——p3 的 0.03 与 p2 的 0.40 显示 Jev 对证据质量敏感，不误放

## 4. 成本实测

单次验收（3 要点 + 2 元问题，5 次 systemone 调用）：input tokens 合计 2,238。
