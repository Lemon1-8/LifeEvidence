# 第三方署名与许可

生活有据是独立开发的 Android 资料检索客户端，非下列项目的官方应用；列出来源不表示原作者为本客户端背书。

## 生活资料：HowToLiveBetter

- 作品：《高性价比人生指南》。
- 作者：**eternity4719 及 HowToLiveBetter 项目贡献者**。
- 原仓库：[eternity4719/HowToLiveBetter](https://github.com/eternity4719/HowToLiveBetter)。
- 内置资料版本：[dc74035713a3d09b5ec4a9a08905170748f54af1](https://github.com/eternity4719/HowToLiveBetter/tree/dc74035713a3d09b5ec4a9a08905170748f54af1)。
- 许可：[Creative Commons Attribution 4.0 International（CC BY 4.0）](https://creativecommons.org/licenses/by/4.0/)；[本地完整许可](app/src/main/assets/licenses/HowToLiveBetter-CC-BY-4.0.txt)。

使用范围为正文条目、读者长文及术语解释。客户端保留原文、章节前言、适用条件、备注和出处；新增分条显示、检索分块、词法索引、语义向量和阅读界面。正文未改写，索引会清理 Markdown 标记及链接格式。App 的内容更新仍从上游仓库的固定完整提交获取，原文页链接到对应提交。

内容许可适用于上游资料及其相应衍生内容，不自动决定独立客户端代码的许可。客户端检索结果不代表已对原文观点另行核验。

## 语义模型：BGE-small-zh-v1.5

- 提供者：**Beijing Academy of Artificial Intelligence（北京智源人工智能研究院，BAAI）**。
- 模型：[BAAI/bge-small-zh-v1.5](https://huggingface.co/BAAI/bge-small-zh-v1.5)。
- 固定修订：[7999e1d3359715c523056ef9478215996d62a620](https://huggingface.co/BAAI/bge-small-zh-v1.5/tree/7999e1d3359715c523056ef9478215996d62a620)。
- 许可：MIT；[保留的版权与完整许可](app/src/main/assets/licenses/BGE-MIT.txt)。

客户端使用由该模型导出的 INT8 ONNX 版本，进行了动态量化，保留 CLS pooling、L2 normalization 及相同 tokenizer。离线预计算和手机推理使用同一模型；处理步骤与哈希见 [资源构建说明](tools/ASSETS.md)。

## 推理运行时：ONNX Runtime

ONNX Runtime 由 Microsoft 及其贡献者提供，采用 MIT 许可。项目使用 ONNX Runtime Android 1.24.3，在设备 CPU 上进行推理。[项目来源](https://github.com/microsoft/onnxruntime)，[保留的版权与完整许可](app/src/main/assets/licenses/ONNXRuntime-MIT.txt)。

应用内也可在设置中离线查看以上署名及许可；原始署名文本位于 [ATTRIBUTION.txt](app/src/main/assets/licenses/ATTRIBUTION.txt)。
