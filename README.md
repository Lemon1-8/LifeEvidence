# 生活有据

一款 Android 离线生活资料检索 App。用平时说话的方式输入问题，先获得关键词结果，再按语义补充排序；点开结果后阅读原文、上下文和出处。

本项目是基于 [eternity4719/HowToLiveBetter](https://github.com/eternity4719/HowToLiveBetter) 内容独立开发的 Android 客户端，非上游官方应用。感谢 **eternity4719 及该仓库的贡献者**提供《高性价比人生指南》。正文、长文和术语解释均来自该仓库，按 **CC BY 4.0** 保留署名、原文链接和许可说明。

App 增加了离线搜索、原文阅读、收藏与内容更新；内容仅做分条、格式处理和索引，未改写正文，也不生成答案。未覆盖的问题可以返回空结果；仅主题相关的材料单独标识。模型采用 [BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)，由北京智源人工智能研究院（BAAI）提供，采用 MIT 许可。完整来源、固定版本及处理说明见 [第三方署名与许可](THIRD_PARTY_NOTICES.md)。

目标设备为红米 Note 12 Turbo / Android 15；最低支持 Android 8。手机安装包为 arm64，模拟器安装包为 x86_64。实际通过的检查、设备页大小、截图和限制以 [测试报告](docs/TEST_REPORT.md) 为准，测试用例存在不代表已执行通过。

1.0.1 版本增加两级折叠目录、目录筛选和阅读定位；本轮构建与测试结果见 [目录改进记录](docs/DIRECTORY_UPDATE.md)，与初次发布安装包的验收记录分开保存。

## 安装与使用

从 [GitHub Releases](https://github.com/Lemon1-8/LifeEvidence/releases/latest) 下载安装包：`LifeEvidence-1.0.1-arm64.apk` 用于手机，`LifeEvidence-1.0.1-x86_64.apk` 用于模拟器。同页提供完整源码、公开验收记录和 SHA-256 校验文件。1.0.1 的测试范围、关键截图和结构化结果见 [目录改进记录](docs/DIRECTORY_UPDATE.md)；初次发布的双模拟器验收保留在 [1.0.0 测试报告](docs/TEST_REPORT.md)。

完成 release 构建后，两个安装包位于：

| 文件 | 用途 |
| --- | --- |
| `app/build/outputs/apk/release/app-arm64-v8a-release.apk` | 红米 Note 12 Turbo 等 arm64 手机 |
| `app/build/outputs/apk/release/app-x86_64-release.apk` | 本项目 Android 15 模拟器 |

将 arm64 APK 传到手机打开安装，按系统提示允许对应文件管理器安装应用。首次安装后无需登录、下载模型或联网，内置资料和语义模型即可使用。

- **搜索**：输入“离职了，社保怎么办”等口语问题。中文输入法组词期间只查关键词，完成选词后再进行语义检索。结果卡片显示原文摘录和出处；已经滚动的列表等待你点击“相关性已更新”后再刷新。
- **目录**：一级章节默认折叠，展开后点选二级原文；支持按章节或标题筛选、切换条目/长文/术语、全部收起和回到顶部。进入阅读或切换标签后保留目录展开状态和位置，阅读页右上角可定位回所在目录。
- **阅读**：搜索结果定位到对应原文段落，保留章节说明和其他上下文；返回后恢复搜索和列表位置。来源链接在浏览器中打开。
- **收藏**：阅读页右上角的书签保存完整原文快照，上游内容变化后仍保留收藏时的版本。
- **历史**：只在主动提交搜索或从搜索结果打开原文时记录。首页可单条删除或清空，设置中可关闭记录；关闭记录不会删除原有收藏。
- **设置与资料**：查看内容版本、手动检查更新、管理历史，以及离线阅读内容许可、模型许可和出处说明。

界面跟随系统深浅色和字体大小。内容范围由上游仓库决定；模型不能补足仓库没有的知识，也不能保证每次都准确理解问题。搜索结果应结合原文适用范围阅读，资料的发布日期和出处可在原文中核对。

## Windows 本地构建

在项目根目录打开 PowerShell。需要 JDK 17、Android SDK 36、Android SDK Build Tools 36.0.0 和可访问依赖仓库的网络。工程自带 Gradle Wrapper，首次构建会下载对应 Gradle 和依赖，无需另外安装系统 Gradle。Python 验证、资源生成和源码打包脚本要求 Python 3.11 或更新版本；资源生成还需安装 `tools/requirements.txt`。

```powershell
./tools/build.ps1 -Tasks assembleRelease
```

构建脚本在本机已安装时使用 `C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot`，否则使用现有 `JAVA_HOME`。SDK 默认读取 `%LOCALAPPDATA%/Android/Sdk`；首次运行生成仅本机使用的 `local.properties`。换电脑时，可先设置自己的 `JAVA_HOME`，并在 `local.properties` 中指定 `sdk.dir`。

release 构建开启代码压缩、混淆和资源压缩，并为两个 ABI 分别生成 APK。运行单元测试：

```powershell
./tools/build.ps1 -Tasks testReleaseUnitTest
./tools/build.ps1 -Tasks testReleaseUnitTest,-PsearchSplit=test
```

依赖已经缓存时可加 `-Offline`；这只控制 Gradle 获取依赖，与 App 的离线搜索功能无关。

**请保留并自行备份项目中的 `.signing` 目录。** 首次构建会在此生成个人安装签名和相应配置，后续 APK 必须使用同一份签名才能覆盖安装并保留本地数据。签名私钥和密码配置已加入 `.gitignore`，不要提交到 Git 或公开分享。丢失后重新生成的签名不能直接覆盖旧安装；卸载旧应用会删除其历史和收藏。

## 模拟器与测试

配置步骤、安装环境和输入法说明见 [模拟器环境](docs/emulator-setup.md)。SDK 命令行工具 23 的准备脚本使用本机 JDK 21；应用构建仍使用 JDK 17。

```powershell
./tools/android/setup-emulators.ps1 -JavaHome 'C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot'
./tools/android/start-emulator.ps1 -Name LifeEvidence_API35
```

默认在后台启动并保存日志；需要手动操作界面时，停止后再加 `-ShowWindow` 启动。两个专用模拟器均为 4 核、4 GB 内存，顺序运行：

| 模拟器 | adb 设备名 | 用途 |
| --- | --- | --- |
| `LifeEvidence_API35` | `emulator-5580` | Android 15 标准页大小环境 |
| `LifeEvidence_API35_16KB` | `emulator-5582` | Android 15 的 16 KB 页环境 |

启动脚本会读取实际 API、ABI、CPU 核数和页大小，不能只根据模拟器名称认定兼容。安装并启动 x86_64 release APK：

```powershell
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
& $adb -s emulator-5580 install -r ./app/build/outputs/apk/release/app-x86_64-release.apk
& $adb -s emulator-5580 shell am start -n app.shenghuoyouju/.MainActivity
```

对当前运行的专用模拟器执行 release 仪表测试：

```powershell
./tools/build.ps1 -Tasks assembleRelease,assembleReleaseAndroidTest
python ./tools/run_emulator_tests.py --serial emulator-5580 --clear --offline
```

切换到 16 KB 环境后执行相同测试：

```powershell
./tools/android/stop-emulator.ps1 -Name LifeEvidence_API35
./tools/android/start-emulator.ps1 -Name LifeEvidence_API35_16KB
python ./tools/run_emulator_tests.py --serial emulator-5582 --clear --offline
./tools/android/stop-emulator.ps1 -Name LifeEvidence_API35_16KB
```

测试覆盖离线语料与模型一致性、检索、更新、搜索与阅读、收藏、历史设置、大字体与深色，以及滚动后的结果稳定性。检索数据位于 `tests/search_cases.json`，调参集和验收集分开。环境日志位于 `artifacts/test-results/environment`；其他截图、性能及执行结果由 [测试报告](docs/TEST_REPORT.md) 汇总。模拟器速度不等同于红米手机速度，未实测的真机性能不会标为达标。

联网检查单独执行，先打开该模拟器的 Wi-Fi；不要与离线首次启动验收混为一项：

```powershell
python ./tools/run_emulator_tests.py --serial emulator-5580 --classes app.shenghuoyouju.NetworkUpdateTest
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
python ./tools/verify_apks.py
```

最终中文组词验收在两台 AVD 上统一使用官方同文 Trime 3.3.12，并将 `General → Inline preedit mode` 设为 `Composing text`；复现初次发布的 15 项仪表测试前也应配置为这一条件。当前默认套件另外包含 8 项目录回归测试，共 23 项。真实中文输入验证使用 `./tools/android/test-ime.ps1`，安装和配置步骤见环境文档。

默认 Gboard 环境的 composing span 注入测试曾遇到 InputConnection 重连竞争并超时，记录为测试环境差异。生产 App 没有依赖同文输入法；本次通过范围也不等于所有输入法均已验证。过程截图、最终运行记录和限制见测试报告。

## 离线内容、模型与更新

内置正文、词法检索数据、BGE 模型、词表和预计算向量均放在 `app/src/main/assets`。初始内容固定于完整上游提交，详情和重建命令见 [离线资源构建说明](tools/ASSETS.md)。

- 使用 Kotlin、Jetpack Compose、Room 和 ONNX Runtime；词法侧包含中文组合词、口语扩展和 BM25，语义侧使用 [BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5) 的 INT8 ONNX 模型，在设备 CPU 上推理。
- 词法和语义分别检索，再融合、按文档去重及过滤；只有相关性足够的材料才展示。不相关内容不会为了凑满结果而补入列表。
- 手机与离线预计算使用相同模型、词表、分块规则和向量处理方式。资源清单保存哈希与索引版本；重建模型后必须同步重建向量。
- 打开应用时，Wi-Fi 下每天最多自动检查一次；自动失败当天不重复尝试。设置中的手动更新使用当前可用网络。
- 更新从 GitHub 获取仓库提交 SHA，再从同一个提交读取所有正文。未变化段落复用已有向量，新增和修改内容在本地计算；候选内容与索引全部验证完成后才切换版本。
- 更新中断、格式不兼容或网络失败时保留原有资料。正在阅读的原文和收藏快照仍保留其来源版本。App 不自动更新安装包或模型文件。

语义检索使用有限的本地模型，首次模型加载通常比后续搜索慢。较长查询或罕见说法可能匹配不准；遇到库外问题时，换个问法也不意味着上游已有答案。

## 隐私与出处

查询、历史和收藏存储在设备本地。搜索不会把问题上传给模型服务；没有账号、广告或统计 SDK，应用备份也已关闭。网络权限用于 GitHub 内容更新；主动点击原文来源或参考链接会交给浏览器访问相应站点。清除应用数据或卸载会删除本地历史、收藏和下载的更新。

上游内容由 eternity4719 及仓库贡献者提供，采用 CC BY 4.0；模型采用 MIT 许可。保留的许可和出处文件位于 `app/src/main/assets/licenses`，可在应用设置中离线查看。内容整理只做分段、索引和显示格式处理，不把检索结果当作重新核实后的专业结论。

## 项目布局

运行 `python tools/package_source.py` 可生成完整源码交付包 `artifacts/生活有据-1.0.1-源码.zip`，包含离线模型、向量、词法索引及测试源码。压缩包不包含签名私钥、本机 SDK 配置、缓存、构建产物和原始设备日志；公开验收证据保存在 `docs/verification`。解压后首次构建会生成新的个人签名，无法直接覆盖使用原交付签名的安装包。

[Release](https://github.com/Lemon1-8/LifeEvidence/releases/latest) 中的 `LifeEvidence-1.0.1-verification.zip` 收录公开报告、关键截图及结构化验收结果，各报告分别注明其对应版本。完整原始日志仍保留在开发机器的 `artifacts/test-results`，未上传到公开仓库。

```text
app/src/main/java/app/shenghuoyouju/
  MainActivity.kt         Android 入口
  MainViewModel.kt        搜索、用户数据和更新状态
  ui/                     Compose 页面与主题
  data/                   内容解析、版本更新与 Room 数据库
  search/                 词法搜索、语义模型与 tokenizer
app/src/main/assets/      固定内容、模型、向量及许可
app/src/test/             JVM 单元测试
app/src/androidTest/      release 仪表测试
tools/build.ps1           构建与个人签名入口
tools/build_assets.py     离线资源重建
tools/android/            模拟器创建、启动与停止
tests/                    检索调参与验收样例
docs/                     环境说明与最终测试报告
artifacts/                本地验证产物和截图
```
