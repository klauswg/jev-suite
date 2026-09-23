# jev-suite

**基于 Jev（TypeSafe System One）的四个判定质量工具，共享同一内核 —— Jev 回答结构化问题，确定性代码保留最终决定权。**

[English README](README.md)

Jev 是一个判定模型：对 state 回答带类型的问题（score / choice / noul）。本套件的每个应用都拿一个真实世界里「事情是否真的按要求发生了」的问题，把消毒后的证据喂给 Jev，同时把阈值、否决、路由留在普通 Java 代码里。

## 应用

| 应用 | 回答的问题 | 状态 |
|---|---|---|
| [jev-proof](jev-proof/) | 赞助视频是否真的兑现了 brief？（创作者内容验收） | ✅ v0.1.0 —— 60 样本校准：门控管线 68/68 全对、12 个设计内弃权、注入攻击 0/10 翻转 |
| [jev-fit](jev-fit/) | 这位候选人与岗位要求的真实差距在哪？ | ✅ v0.1.0 —— 60 样本校准：门控管线 115/115 全对、5 个设计内弃权、注入攻击 0/20 翻转 |
| jev-fidelity | 编辑是否保留了原文的信息？ | 📋 PRD + 开发方案已完成，实现排队中 |
| jev-rental | 看房前必须向中介确认哪些事项？ | 📋 PRD + 开发方案已完成，实现排队中 |

## 共享内核：jev-kit

- `client/` —— `JevClient` 接口 + 带类型答案（`ScoreAnswer` / `ChoiceAnswer` / `NoulAnswer`）、TypeSafe HTTP 客户端（429/5xx/超时分流）、离线开发用的关键词 fixture mock
- `gate/NoulGate` —— 三态门控：高置信通过 / 置信否决 / 其余一律转人工
- `state/ExternalStringSanitizer` —— 一切外部文本的注入攻击消毒

每个应用都遵守的设计规则：

1. **Jev 判定，代码决定。** 阈值、路由矩阵、否决规则都住在可单测的代码里。
2. **向谨慎方向降级。** 模型不可达 → 转人工/升级，永不自动放行。
3. **评估拒绝 mock 数据。** 校准执行器检测不到 API key 就硬失败（exit 2）；`--allow-mock` 会给输出加水印。
4. **证据先于判定。** 检索不到证据 → 该检查失败关闭；模型没机会编造依据。

## 构建

需要 JDK 17+ 和 Maven。jev-proof 额外需要 [yt-dlp](https://github.com/yt-dlp/yt-dlp)。

```bash
mvn package
```

## License

MIT
