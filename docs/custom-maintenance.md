# Mika 定制版维护约定

正式分支为 Zephyrxxx/Mika 的 main。以 merge commit 保留上游祖先关系。每次同步对照共同祖先、当前 main 的全部定制和上游改动；本表只列已知约束。只有原生通话页面和模型建立双向音频后才报告 AI 托管。

| 功能 | 源码与验收 |
| --- | --- |
| 并排安装与升级 | app/build.gradle.kts、AndroidManifest.xml、custom_identity.xml。包名 com.wangzan101.mika.phonecompat，固定签名 SHA-256 911fdd3e3f92446e630bf329ee171675bcf6dd31d59f3cf2a43312748fa1d531。四个 Provider 使用 applicationId，tel Intent 由用户选择的电话角色接收。 |
| 系统电话与胶囊 | calls/NativeCallOverlay、NativeCallAudio、NativeCallLog、CallRuntime、capsule/CapsuleController。保留 vivo 默认电话，AI 接管、交回、再次接管；双 SIM、静音回读和还原、来电等待、锁屏与异常恢复均需真机。 |
| AI 电话角色 | CallTelecomAccess、MikaInCallService、RoleDialerUi。无人值守的自动接听要求真实角色、实际授权、实测音频、已配置语音和通知、允许接听的号码范围与待命条件。保留系统电话时，用户可用接听权限在响铃时点悬浮栏接起当前来电，也可在通话接通后交给 AI。两种方向都在建立语音会话时读取通话说明与当时生效的交代。 |
| 记录 | CallHistory、CallHistorySection。挂断后合并人工/AI 时间段，仅 AI 段转写，按原设置生成摘要，不保存可播放录音。保留旧数据格式。 |
| 更新器 | about/MikaLinks、UpdateChecker、Releases、AppVersion、ApkUpdater、ApkIntegrity。GitHub API 和下载白名单都指向 Zephyrxxx/Mika，旧来源缓存失效。ARM64 优先，回退通用包；APK 签名、包名、较高 versionCode、HTTPS 及发布摘要校验保持有效。 |
| 验证 | 原 CI 的 core、app、cli、relay，加上 .github/scripts/test_custom.py 和 about/calls 下既有单测。不得降低验证门槛。 |

真机验收用 vivo V2405A / Android 16。查看默认电话、已启用无障碍和助手连接。测试双方同意的通话，人工→AI→人工→AI，检查双方音频、麦克风及记录。测试空名单不接听、允许的来电可待命接听，随后恢复测试前设置。使用系统安装确认覆盖旧版，核对记录和设置。不卸载、不清数据。
