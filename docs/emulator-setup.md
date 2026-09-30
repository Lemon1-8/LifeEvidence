# Android 模拟器环境

本项目使用两个专用 Android 15 / API 35 / x86_64 AVD。每台 4 核、4096 MB 内存，顺序运行以避免争抢资源。脚本不会删除或改动其他 AVD。

| AVD | 系统镜像 | adb 设备名 | 预期页大小 |
| --- | --- | --- | --- |
| LifeEvidence_API35 | `system-images;android-35;google_apis;x86_64` | `emulator-5580` | 4096 |
| LifeEvidence_API35_16KB | `system-images;android-35;google_apis_ps16k;x86_64` | `emulator-5582` | 16384 |

## 已安装环境

- SDK：`%LOCALAPPDATA%/Android/Sdk`
- Java：`C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot`
- Emulator：36.5.11；WHPX 可用（实际检查见 `artifacts/test-results/environment/hypervisor.txt`）。
- Command-line Tools：23.0，位于 `cmdline-tools/latest`；原 12.0 保留在同级 `12.0-legacy`。
- 标准 API 35 镜像 revision 9，16 KB 镜像 revision 5。
- 23.0 的 `sdkmanager` 会提示改用 Android CLI，并调用其内置兼容入口；这与旧版 SDK XML 解析警告不同。完整安装清单在 `artifacts/test-results/environment/sdk-installed.txt`。

## 创建与启动

在项目根目录打开 PowerShell。脚本仅临时设置当前进程的 Java 环境，不改系统环境变量。

```powershell
./tools/android/setup-emulators.ps1 -JavaHome 'C:/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot'
```

已安装镜像时可加 `-SkipInstall`。需要使用本机 HTTP 代理时，显式加 `-ProxyHost 127.0.0.1 -ProxyPort 你的端口`；脚本不改系统代理。

启动默认不显示窗口，使用软件 GPU、关闭快照；等待系统完成启动并实测 API、ABI、页大小。

```powershell
./tools/android/start-emulator.ps1 -Name LifeEvidence_API35
# 在普通镜像完成验证后再切换：
./tools/android/stop-emulator.ps1 -Name LifeEvidence_API35
./tools/android/start-emulator.ps1 -Name LifeEvidence_API35_16KB
```

需要手动操作时，先停止对应 AVD，再用启动命令加 `-ShowWindow`。启动脚本发现另一台专用 AVD 运行时会停止继续执行；不会关闭其他 AVD。停止脚本会核对端口对应的 AVD 名称。

日志在 `artifacts/test-results/environment/`：`*-stdout.log`、`*-stderr.log` 是最近一次启动日志；`*-boot.json` 只在系统启动完成后生成，包含实际页大小和验证时间。标准镜像已确认 4096；16 KB 镜像最终验证以其 `*-boot.json` 及应用测试报告为准，不能仅凭 AVD 名称确认。

## 离线与联网仪表测试

需要 Python 3.11 或更新版本，并先构建 release 应用和测试 APK。以下命令只用于专用测试 AVD；`--clear` 会清除该 App 的历史、收藏和已下载内容。

```powershell
./tools/build.ps1 -Tasks assembleRelease,assembleReleaseAndroidTest
python ./tools/run_emulator_tests.py --serial emulator-5580 --clear --offline
```

默认执行 `CoreIntegrationTest`、`UiFlowTest`、`ResultStabilityTest`。`--offline` 会关闭联网并探测外网不可达；运行结束保持离线，便于复核首次离线启动。联网更新使用独立的 `NetworkUpdateTest`，先显式恢复网络并等 Wi-Fi 连接成功：

```powershell
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
& $adb -s emulator-5580 shell cmd connectivity airplane-mode disable
& $adb -s emulator-5580 shell svc wifi enable
python ./tools/run_emulator_tests.py --serial emulator-5580 --classes app.shenghuoyouju.NetworkUpdateTest
```

切换到 16 KB AVD 后将设备名改为 `emulator-5582`，保持两台顺序运行。每次执行会在 `artifacts/test-results/<设备名>/<时间>/` 保存 APK SHA-256、运行摘要、仪表输出和日志；最终通过项以 [测试报告](TEST_REPORT.md) 为准。

完整 15 项仪表测试包含 composing span 注入；两台 AVD 均需先按下文配置同文 Trime 3.3.12 和 `Composing text`，才能复现最终验收环境。默认 Gboard 环境曾在该注入步骤遇到 InputConnection 重连竞争并超时；这是已记录的测试环境差异，不能据此断言生产 App 依赖同文，也不能将同文通过扩大为所有输入法均通过。

## APK 与中文输入

```powershell
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
& $adb -s emulator-5580 install -r ./app/build/outputs/apk/release/app-x86_64-release.apk
& $adb -s emulator-5580 shell getconf PAGE_SIZE
```

中文输入使用 [官方同文输入法 Trime v3.3.12](https://github.com/osfans/trime/releases/tag/v3.3.12) 的 x86_64 包。下载文件保存为 `artifacts/downloads/trime-v3.3.12-x86_64.apk`，SHA-256 为 `0B32FEE993F59614A69269AEA30F817C48D7AF67B35965ED4EB107E68340EE6F`。

```powershell
& $adb -s emulator-5580 install -r -g ./artifacts/downloads/trime-v3.3.12-x86_64.apk
& $adb -s emulator-5580 shell ime enable com.osfans.trime/.ime.core.TrimeInputMethodService
& $adb -s emulator-5580 shell ime set com.osfans.trime/.ime.core.TrimeInputMethodService
& $adb -s emulator-5580 shell am start -n com.osfans.trime/.MainLauncherAlias
```

首次启动选择应用专用存储（`Use app-specific storage`），完成引导。两台镜像均已完成此配置；手动重建 16 KB 镜像时，将命令中的设备名改为 `emulator-5582`，照同样流程操作。普通镜像早期输入 `nihao` 得到“你好”的证据为 `LifeEvidence_API35-ime.xml` 和 `LifeEvidence_API35-ime.png`；16 KB 镜像的完整配置过程保存在 `artifacts/test-results/ime-16kb-setup/`。

若要让 App 真正收到组词文本，在同文设置 `General → Inline preedit mode` 中选择 `Composing text`。两台镜像已完成此配置；默认 `Disable` 只在输入法自己的候选栏保留拼音，不能用于验证 App 的 composing span 分支。

## 真实 App 的输入法组词检查

待 APK 安装完成且没有其他设备测试运行时执行：

```powershell
./tools/android/test-ime.ps1 -Serial emulator-5580
```

脚本使用已有同文输入法，在真实 App 搜索框输入 `shebao`，保留未提交候选词至少 2 秒，再按空格选择首个候选词，并断言提交文字确为“社保”、随后出现实际正文结果。它会重新启动 App 以清空本次查询状态，但不清除应用数据、不替换输入法、不切换 AVD；结束后保留社保结果。

证据写入 `artifacts/test-results/ime/`：组词前、组词中、提交后、最终结果的 PNG 与 UI Automator XML，以及输入法状态、应用日志和 `result.json`。截图取自真实模拟器，不生成或合成画面。

同文可能只在候选栏保留拼音，App 输入框仍为空。脚本区分这种情况与 App 真正收到 composing span 后显示“输入完成后，继续查找相关原文”的情况；二者不混作同一覆盖项。最终 `ime-final/` 与 `ime-16kb-final/` 证据中，真实输入框收到 `she bao‸` 并显示该组词提示；提交后实际读取“找到 6 份相关原文”，两份 `semanticCompletionVerified` 均为 `true`。

部分 Compose 界面在 UI Automator XML 中不暴露状态行。脚本遇到这种情况，以连续两次稳定显示的实际正文标题确认结果可见，并把 `semanticCompletionVerified` 记为 `false`；这个通过结果只覆盖真实输入、组词和提交后可见结果，不能把词法结果当作语义检索完成。组词阶段检查没有语义进度提示；结合 `MainViewModel.changeQuery(..., composing)` 与 `SearchEngine` 的分支核对实现。该黑盒检查没有模型调用计数器，不能仅凭静态截图声称已经测量了推理调用次数。

## 搜索验收数据

`tests/search_cases.json` 包含 64 条来源于当前正文边界的查询，前 5 条保留用户原话。`dev` 与 `test` 各 32 条；只用 `dev` 调参，`test` 保留最终验收。

- `direct`：正文有直接回答查询的条目。
- `related`：有同主题依据，但无法满足查询要求的具体处方、品牌、预测或完整方法。
- `none`：固定正文及读者长文未找到对应主题。不能用“网络钓鱼”回答休闲钓鱼，也不能把账号安全当电脑维修。
- `targetTerms`：可接受的目标标题片段，任一命中即满足定位条件；无覆盖案例为空数组。所有非空片段都已在 `corpus.json` 的文档标题中核对，数据集不等同于搜索通过报告。

数据依据：上游提交 `dc74035713a3d09b5ec4a9a08905170748f54af1`。这些是检索相关性标注，不是对医疗、法律或金融内容的重新核实。
