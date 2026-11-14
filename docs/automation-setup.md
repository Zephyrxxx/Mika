# GitHub Actions 首次部署

正式版在 GitHub 的 ubuntu-latest 构建。北京时间 00:30 对应 UTC 16:30；GitHub 调度可能延迟。仓库变量 MIKA_SYNC_ENABLED 初始留空。验收后设为 true。手动运行 Upstream sync 不依赖这个变量。

1. 在 Settings → Actions → General 启用 Actions，允许创建 PR。在合并选项中允许 merge commit。如启用 main 保护，保留必需检查；不要允许绕过。CI 在 PR 和同步链上运行。仓库无需打开 GitHub 原生 auto-merge 选项：工作流在完整 CI 成功后以指定候选 SHA 合并。
2. 在仓库 Actions secrets 保存 OPENAI_API_KEY。ChatGPT 登录不能充当 API Key。设置 API 项目预算。一次同步最多两个候选生成任务，各调用一次 Codex；CI 失败仅修复一次。
3. 创建名为 mika-release 的 Environment，限制部署来源到 main，个人无人值守时无需配置人工审核。仅此环境保存 MIKA_KEYSTORE_B64 和 MIKA_KEYSTORE_PASSWORD。使用原 phonecompat PKCS12、别名 phonecompat。通过网站的 Secrets 输入框或用 gh secret set 从标准输入写入；不可在终端打印或提交密钥。独立加密备份 keystore 与密码。缺失原钥时暂停发布。
4. 首次合并部署 PR 要等 CI 全绿。在 Actions 核对 Publish 的 select、verify、build、publish 和 Releases 两个附件。后续 GITHUB_TOKEN 合并不会依赖 push 间接触发：同步工作流显式调用 CI 与发布。普通 main push 也运行发布。冲突/检查失败保留原 PR，详见运行日志。CI 改动涉及可信工作流时由维护者单独检查合入。
5. 首次验收在手机上手动安装验证过的定制包。旧 phonecompat.4 的更新器指向上游，因此首次迁移需要手工覆盖安装。从此次正式版开始，应用内检查 fork Releases。安装由用户确认。验收两个顺序 release 后再开启定时变量。
6. 版本采用 main 首父链提交数 N，tag v1.0.N、versionName 1.0.N、versionCode 100000+N。从完整 main 历史构建。复现旧版使用历史 tag、相同 JDK/SDK、Gradle wrapper 和相同签名，传 -PcustomBuild=N。功能回退通过新 PR revert 产生更高版本；旧 APK 留存，不默认降级。
7. 重试发布使用 main 的 Publish 手动启动；已发布且 tag 对应同一 SHA 会跳过。草稿只有在两个包签名、包名、版本和哈希校验后转正式。历史发布不删除。供应方停止推送、fork Actions 被 GitHub 停用、额度耗尽或保护规则不允许合并时查看 Actions 日志。
