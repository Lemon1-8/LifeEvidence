# 生活有据 1.0.0 验收记录

测试日期：2026-09-30。只把有执行证据的项目记为通过；下文区分桌面检索评估、真实模拟器和未实测的手机。

本报告对应初次发布安装包。1.0.1 目录交互改进的安装包与验证结果见 [目录改进记录](DIRECTORY_UPDATE.md)。

本公开报告保留验收数字、APK 哈希和关键截图。结构化结果中的本机绝对目录改为相对目录，JUnit 的主机名匿名化；原始 logcat、系统崩溃缓冲区与环境安装日志仍保留在开发机器，未上传。下方崩溃摘要仅公开进程、异常及原日志哈希，未改变测试结果。

## 固定资源与方法

- 上游提交：`dc74035713a3d09b5ec4a9a08905170748f54af1`；686 份文档（640 正文、5 长文、41 术语），3827 个检索块。
- BGE-small-zh-v1.5 修订：`7999e1d3359715c523056ef9478215996d62a620`；CPU INT8 动态量化，512 维、CLS + L2，查询添加固定检索前缀。
- 模型 SHA-256：`2c050373e3652a628c4ea6e0476b956c793754001c8633c95a2f123083a9d5cc`。与语料、词表、向量的版本清单在 `app/src/main/assets/model_manifest.json`。
- 测试机：Windows，Intel Core i9-13900HX，约 32 GB 内存，WHPX；模拟器均配置 4 核、4 GB RAM，顺序运行。
- 测试应用为启用 R8、资源压缩并使用个人签名的 release APK；测试框架共享的 Kotlin/AndroidX 二进制接口通过保留规则维持兼容，应用其余代码仍进行压缩和混淆。

## 独立检索评估

64 条样例分为 32 条调参集和 32 条独立验收集。相关性阈值仅在调参集确定。后续 UI 回归发现短关键词“社保”被过滤过严，增加了通用的短查询标题完整匹配规则，并重新执行独立集；没有针对独立集失败样例调整规则。最终独立集仍为 23/24。

| 数据集 | 直接相关条目前三命中 | 库外问题正确空结果 |
| --- | --- | --- |
| 调参集 | 23/23（100%） | 4/4 |
| 独立验收集 | 23/24（95.8%） | 4/4 |

证据：[调参结果](verification/search/dev.json)、[独立结果](verification/search/test.json)。计分同时要求目标原文进入前三并被判为直接相关。唯一未通过的独立样例是“有人说能替我代评职称和代写论文，靠谱吗”：对应原文已排第一，但被保守地标成“仅主题相关”。其余主题相关样例允许返回带标识的原文或空结果。该指标只描述此小型标注集，不代表任意生活问题都能准确检索。

JVM 测试 11/11 通过，覆盖完整语料解析一致性、tokenizer、资源哈希/向量、词法索引、检索评估及 Git 引用协议解析；原始结果保存在 [JVM 结果目录](verification/jvm/independent/)。

| 用户原始问题 | 验收行为 |
| --- | --- |
| 离职了，社保怎么办 | 社保断缴原文进入前三 |
| 轻微感冒，吃点什么药？ | 返回感冒用药安全相关原文，阅读保留适用范围 |
| 到底要怎么才能完全防晒 | 返回无合适内容，未拼接为完整防晒方案 |
| 钓鱼的技巧有哪些 | 返回空结果，未误推网络钓鱼 |
| 电脑开不了机了，是什么情况 | 返回空结果，未误推账户安全等内容 |

## 模拟器执行结果

| 设备 | 实测页大小 | 清数据、断网完整套件 | 真实联网更新 | 跨进程保存 |
| --- | --- | --- | --- | --- |
| LifeEvidence_API35 | 4096 字节 | [15/15 通过](verification/emulator-5580/20260930-153214/summary.json) | [1/1 通过](verification/emulator-5580/20260930-153447/summary.json) | [写入通过](verification/emulator-5580/20260930-153404/summary.json)、[新进程读取通过](verification/emulator-5580/20260930-153426/summary.json) |
| LifeEvidence_API35_16KB | 16384 字节 | [15/15 通过](verification/emulator-5582/20260930-154201/summary.json) | [1/1 通过](verification/emulator-5582/20260930-153916/summary.json) | [写入通过](verification/emulator-5582/20260930-154450/summary.json)、[新进程读取通过](verification/emulator-5582/20260930-154501/summary.json) |

实际读取设备 API、ABI 和页大小，两个 AVD 均为 Android 15 / API 35 / x86_64、4 核、4 GB RAM。启动记录：[标准设备](verification/environment/LifeEvidence_API35-boot.json)、[16 KB 设备](verification/environment/LifeEvidence_API35_16KB-boot.json)。所有最终仪表运行均保存应用与测试 APK 的 SHA-256、安装结果、网络前置条件、instrumentation 输出、logcat 和崩溃缓冲区。

核心离线套件的 15 项测试包括：

- 清空应用数据、关闭 Wi-Fi/移动数据并实际探测外网不可达；直接运行内置模型，与主机端 5 组向量逐项比较。
- 五个用户问题、独立词法/语义阶段、库外空结果，三种原生库实际加载。
- 真实重算 1 个修改段落并复用 3826 个向量；激活前中断不改旧指针，成功后能重新读取完整新快照。
- 拒绝缺少文件、条目格式变化、不完整提交、空候选、孤立段落和重复文档共 6 种错误候选。
- 搜索、定位原文、收藏、返回位置、连续修改/清空、光标移动不重查、中文 composition 阶段只查词法。
- 关闭/清空历史、Activity 重建、目录浏览、深色和 1.3 倍字体，以及滚动期间暂缓重排。

跨进程保存另用两次仪表进程验证：第一进程保存/复用真实历史和收藏，强制停止后第二进程核对完整快照、版本与时间，并验证单条历史删除。它与单纯旋转屏幕的重建测试分开。

上述完整套件和联网运行未发现 `app.shenghuoyouju` 或其测试进程崩溃。16 KB 系统镜像的后台 Google Play 服务出现一次 Java 异常，Google 搜索进程出现一次 SIGILL；测试均正常完成，公开[联网崩溃摘要](verification/emulator-5582/20260930-153916/crashes.json)和[离线崩溃摘要](verification/emulator-5582/20260930-154201/crashes.json)可查，不将这些系统进程事件记为 App 崩溃。完整原始缓冲区保留在开发机器。

## 模拟器性能实测

以下是最终完整套件中的原始耗时，单位毫秒；不是红米手机基准。每次套件均清空 App 数据，模型首次查询前不存在本地模型缓存。

| 指标 | 标准 4 KB | 16 KB |
| --- | --- | --- |
| 内容与索引加载 | 161.7 | 239.7 |
| 首次模型复制、会话创建与查询 | 355.6 | 305.1 |
| 模型预热查询 P50（19 次） | 2.6 | 4.1 |
| 模型预热查询 P95（19 次） | 9.4 | 5.4 |
| 五个问题的词法阶段耗时范围 | 2.3–45.3 | 5.8–52.3 |
| 五个问题的最终阶段耗时范围 | 212.0–409.5 | 216.8–386.8 |
| 修改 1 块、复用 3826 块并发布候选 | 857.3 | 661.3 |
| 真实 GitHub 下载、解析并发布新版 | 18846.5 | 24143.9 |

模型计时只包含推理调用及首次必要初始化。搜索阶段从 `SearchEngine.search` 开始，包含两阶段间 200 ms 等待，不包含 ViewModel 输入侧 150 ms 防抖、IME 和界面绘制；不能把它当作停字后的完整 P95。原始逐次数据：[4 KB 模型](verification/emulator-5580/20260930-153214/device-reports/model-parity.json)、[4 KB 搜索](verification/emulator-5580/20260930-153214/device-reports/offline-search.json)、[16 KB 模型](verification/emulator-5582/20260930-154201/device-reports/model-parity.json)、[16 KB 搜索](verification/emulator-5582/20260930-154201/device-reports/offline-search.json)。

16 KB AVD 刚创建启动时的首次运行曾测到模型冷查询 4480.4 ms、预热 P95 126.2 ms；该轮组词注入测试失败，仍保留[当时的性能原始记录](verification/emulator-5582/20260930-153731/device-reports/model-parity.json)。这说明模拟器后台状态会影响结果，本报告不把后续较快的一轮推广成手机性能承诺。

## 更新与断网恢复

生产版本查询使用 Git 官方 smart HTTP `info/refs` 协议读取完整 `refs/heads/main` SHA，避免匿名 GitHub REST 配额耗尽影响个人使用。随后 README 和全部正文都从同一 SHA 的 raw URL 获取；不调用在线模型。

联网验收实际获取并安装了 `d63794d5dba477ca42b3309a5ae5b396f330c35f`。测试先把内存中的版本标识设为测试专用的 40 个 `a`，强制走真实下载、解析、索引和持久化流程；此标识不冒充上游历史提交。联网测试结束后恢复测试前的数据指针。

自动成功检查和自动失败检查均验证了当天不再发起第二次请求。失败场景保持 Wi-Fi 连接，在测试进程中把请求导向无服务的 `127.0.0.1:9`，实际发生 socket 连接失败后阻止 Android 的直连回退；记录代理选择/连接失败次数，确认当天再次调用时次数不增加。这是测试注入，生产代码没有这套代理逻辑。选择器与响应缓存最终恢复。另关闭 Wi-Fi/移动数据，真实验证 DNS/网络失败时内存版本与持久化指针不变，并从磁盘重新加载新版后离线完成语义检索，结果、正文和来源 SHA 一致。

中断和格式不兼容由离线核心测试的候选快照验证；真实 GitHub 更新由单独联网用例验证，二者不混为同一项。

原始更新证据：[4 KB](verification/emulator-5580/20260930-153447/device-reports/network-update.json)、[16 KB](verification/emulator-5582/20260930-153916/device-reports/network-update.json)。

## 中文输入与界面证据

标准设备在清空数据并断网后，用同文输入法 3.3.12 实际输入 `shebao`。组词时输入框为 `she bao‸`，App 显示“输入完成后，继续查找相关原文”；选择候选后提交“社保”，完成状态显示 6 份原文。该脚本读取真实 UI；推理阶段判断另由仪表测试直接核对 ViewModel 状态。[前置条件](verification/ime-final/preconditions.json)、[实际输入结果](verification/ime-final/result.json)。

16 KB 首轮的默认 Gboard 在注入组词时发生 InputConnection 重连，composition 合取条件超时。统一为同版本同配置同文后，相同 APK 和测试断言的完整 15 项通过。这个对照支持输入会话竞争的解释，但没有证据确认 Gboard 调用了哪一项提交操作；App 不依赖同文，其他厂商输入法尚未逐一实测。

16 KB 设备也在清空数据、确认外网不可达后完成了真实中文首次启动检索，组词提示、候选提交和最终“找到 6 份相关原文”状态均通过：[前置条件](verification/ime-16kb-final/preconditions.json)、[实际输入结果](verification/ime-16kb-final/result.json)、[结果截图](verification/ime-16kb-final/04-final-results.png)。

关键截图均来自真实 release App：

| 场景 | 截图 |
| --- | --- |
| 口语搜索与第一条社保断缴原文 | [搜索结果](verification/emulator-5580/20260930-153214/device-reports/screenshots/02-social-insurance-results.png) |
| 完整原文、章节说明与书签入口 | [原文阅读](verification/emulator-5580/20260930-153214/device-reports/screenshots/03-original-reading.png) |
| 收藏快照 | [收藏](verification/emulator-5580/20260930-153214/device-reports/screenshots/04-favorites.png) |
| 钓鱼技巧不误推网络钓鱼 | [空结果](verification/emulator-5580/20260930-153214/device-reports/screenshots/05-out-of-corpus.png) |
| 深色与大字体 | [深色首页](verification/emulator-5580/20260930-153214/device-reports/screenshots/06-dark-large-font.png) |
| 中文组词尚未提交 | [组词中](verification/ime-final/02-composing.png) |
| 候选提交后的真实结果 | [输入完成](verification/ime-final/04-final-results.png) |

## APK 完整性

[包校验报告](verification/packages/verification.json) 确认：两个 ABI 的全部 16 项 assets、2 个 DEX 哈希一致；各自均包含 graphics-path、ONNX Runtime 和其 JNI 三个原生库；所有 ELF `PT_LOAD` 对齐至少 16 KB，`zipalign -P 16` 和签名验证通过，两包使用同一证书。实际 16 KB 加载结果由模拟器用例验证，静态对齐检查不替代运行测试。

| 安装包 | 大小 | SHA-256 |
| --- | --- | --- |
| arm64 手机 APK | 102,553,101 字节（97.80 MiB） | `0b9f2a06611eec151c5b9bedfbadd7243c14c6d28433bccfc839a8f9d6c02326` |
| x86_64 模拟器 APK | 108,030,180 字节（103.03 MiB） | `f93ac927d78e39b136a7fc242e3022ab9aa78ea98a3be12c1c6693a123a70e6b` |

## 验收中修复的问题

- 正式压缩后的测试 APK 与目标 App 共享的 Kotlin、序列化及 AndroidX 接口不一致：补齐保留规则并重新在 release 上运行。
- 关键词“社保”被语义阈值过度过滤：增加通用短查询完整标题匹配，复测独立集。
- 顶部自动排序被旧卡片 key 的位置恢复覆盖：仅在获准应用的新排序上更新列表 key 版本，滚动中的列表保持原样；使用无中间等待的真实首条可见性断言复测。
- 深色系统栏背景不一致：补齐夜间主题资源，截图复核。
- 模拟器关闭后 adb 列表短暂残留：停止脚本等待设备退出，启动脚本重新核查过期列表。

保留了调试期间的失败日志。最终结论只引用上表确认的最终 APK 运行，不把“存在测试代码”作为通过证据。

## 范围与限制

- 尚未在红米 Note 12 Turbo 真机运行。词法 P95 ≤300 ms、预热语义 P95 ≤1.5 秒仍为真机验收目标，不能用 Intel 模拟器数据宣布达标。
- 搜索提供仓库原文，资料覆盖面和时效取决于上游；没有生成式回答，也没有把库外问题转交在线模型。
- 首次加载模型与后续推理耗时分开记录；搜索流程还有 150 ms / 350 ms 防抖，模型推理耗时不是完整界面响应时间。
- 受控增量更新测试修改测试候选快照以触发真实模型重算；真实 GitHub 检查在独立联网测试执行，不把受控候选冒充已发布的新上游提交。
