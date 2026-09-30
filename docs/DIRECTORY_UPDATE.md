# 生活有据 1.0.1：目录交互改进与验证

日期：2026-09-30。本记录对应 1.0.1（versionCode 2）的 release APK；初次发布记录继续保留在 [TEST_REPORT.md](TEST_REPORT.md)，两轮安装包哈希不同。

## 操作变化

- 使用原书的 36 个一级章节，默认折叠；点击展开二级文章或术语，共 686 份内容，顺序与来源保持一致。
- 目录顶部固定提供章节/标题筛选及条目、长文、术语类型筛选，显示匹配数量。输入关键词自动展开匹配章节，清空后恢复原来的手动展开状态；无结果时可转到全文搜索。
- 支持全部收起和回到目录顶部；已收藏文章显示书签标记。
- 阅读返回、切换标签和界面状态恢复保留目录展开、筛选和滚动位置。阅读返回按钮按实际来源显示“返回目录”“返回收藏”或“返回搜索结果”。
- 阅读页右上方的列表按钮可定位回文章所在目录；已不在当前资料库中的旧收藏不显示此按钮。

## 本轮验证

| 项目 | 实际结果 |
| --- | --- |
| release JVM 单元测试 | 20/20 通过，其中新增目录模型测试 9 项 |
| release 安装包与测试包构建 | 成功，包含 arm64 与 x86_64 安装包 |
| APK 完整性与升级签名 | 两种 ABI 的资源与 DEX 一致，签名和 16 KB 静态对齐校验通过；证书与 1.0.0 相同 |
| Android 15 / API 35 / x86_64 / 4 KB 模拟器 | 13/13 通过：目录交互 8 项、搜索结果稳定性 4 项、深色与 1.3 倍字体 1 项 |
| 真实内容界面复核 | 已确认默认折叠、展开后标题层级、点击阅读及系统返回 |

结构化记录及 APK SHA-256：[summary.json](verification/directory-update/summary.json)。最终仪表原始输出：[instrumentation.txt](verification/directory-update/instrumentation.txt)。截图：[默认折叠](verification/directory-update/collapsed.png)、[展开二级原文](verification/directory-update/expanded.png)。

两种 ABI 的完整静态校验记录：[packages.json](verification/directory-update/packages.json)。静态 16 KB 对齐校验不等于本版本在 16 KB 模拟器或真机上重新运行。

目录专项测试使用内存样本；大字体检查和界面截图使用内置真实语料。此次未清除应用数据，未执行会清空历史的用例，也未重跑联网更新、16 KB 环境及红米真机测试。首轮失败由测试引用已被压缩的辅助类导致，改用界面语义定位；之后补齐系统返回前后的界面就绪等待。失败记录保存在本机 `artifacts/test-results/emulator-5580`，1.0.1 发布包最终通过记录为 `20260930-163346`。

复现本轮检查：

```powershell
./tools/build.ps1 -Tasks testReleaseUnitTest,assembleRelease,assembleReleaseAndroidTest -Offline
python ./tools/run_emulator_tests.py --serial emulator-5580 --classes "app.shenghuoyouju.DirectoryNavigationTest,app.shenghuoyouju.ResultStabilityTest,app.shenghuoyouju.UiFlowTest#largeFontAndDarkThemeKeepPrimaryActionsReachable"
```

完整默认仪表套件已加入 8 项目录测试，共 23 项；这一总数是当前用例数量，本轮实际执行的是上表的 13 项。实现参考了通过 Context7 查询的 [Compose 列表与稳定 key 文档](https://developer.android.com/develop/ui/compose/lists)及[无障碍默认行为](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)。
