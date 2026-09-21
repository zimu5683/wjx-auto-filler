# T10 dry-run 干跑工具

**用真实引擎代码回答一个问题：这个问卷，引擎到底会发什么？**

- 默认模式 **只 GET 页面，绝不 POST**。
- 只有同时给出 `--submit` 与 `--confirm-own-survey` 才会真的提交（只允许提交你自己的问卷）。
- 不占用 Gradle：用离线 kotlinc 编译 `android/.../wjx/` 的真实源码 + `DryRun.kt`，再用 java 运行。

## 用法

```bash
# 干跑（安全，默认）
bash tools/wjx-probe/dry-run/run.sh "https://www.wjx.cn/vm/Q0DQewW.aspx"

# 指定答案（题号=值，逗号分隔）
bash tools/wjx-probe/dry-run/run.sh "https://www.wjx.cn/vm/Q0DQewW.aspx" --answers="1=张三,2=2024001,3=生物1班"

# 真实提交（两把钥匙都要）
bash tools/wjx-probe/dry-run/run.sh "https://你自己的问卷.aspx" --answers="..." --submit --confirm-own-survey
```

## 输出内容

1. 抓取耗时、title/shortId、`useAliVerify` / `captchaType` / `sceneId`、`jqnonce` / `ktimes` / `startTime`、cookies 名、题目清单（题号/题型/选项数）
2. 将要提交的答案（`--answers` 指定，或按题型自动合成——**仅用于复核参数**）
3. 字段匹配结果（`WjxAnswerMatcher`）
4. **将要 POST 的完整 URL**（真实引擎 `WjxSubmitRequest.buildSubmitUrl`）
5. **将要 POST 的 body 原文 + 解码后可读形式**（真实引擎 `WjxSubmitRequest.buildSubmitBody`）
6. 提交门判定：`useAliVerify=1` → 引擎会本地直接返回 E_CAPTCHA，不发网络请求
7. 仅当显式授权时才有的真实提交结果（ok / httpStatus / errorCode / message / raw）

## 用途

- **用户设备上提交失败时**：让用户把链接发来，跑一次干跑，即可复核「参数构造是否正确」而不产生任何答卷。
- **qa-build 集成冒烟**：可以拿它的输出对照自己的期望值。
- **排障**：能直接看到 `jqnonce/ktimes/jqsign/submitdata` 原文，和浏览器 DevTools 抓到的请求逐字段对比。

## 注意

- 依赖 Gradle 缓存里的 jar（kotlin-compiler-embeddable / stdlib / coroutines / trove4j / annotations），本机已具备。
- 输出样例见 `tools/wjx-probe/evidence/05-dry-run-sample.txt`。
