# 离线资源的可复现构建

在项目根目录运行 `python -m pip install -r tools/requirements.txt`，再运行 `python tools/build_assets.py`。
需要 Git 和 Python 3.12；PyTorch 的 CPU 版和同版本 CUDA 版均可，导出及推理始终在 CPU 上进行。
GitHub 与 Hugging Face 下载均固定到脚本中的完整提交 SHA。下载缓存位于 `tools/.cache`，上游只读检出位于 `tools/upstream`。

初始语料为 `dc74035713a3d09b5ec4a9a08905170748f54af1`：640 条正文、5 篇面向读者的长文、41 个术语，共 686 文档、3827 个检索块。文档保留完整原文及章节前言，索引排除维护文档及参考链接 URL。搜索索引的文本格式清理不改写原文，署名和 CC BY 4.0 完整许可在 assets/licenses。

模型是 `BAAI/bge-small-zh-v1.5`，revision `7999e1d3359715c523056ef9478215996d62a620`。Torch eager attention 导出 ONNX opset 17，CLS pooling 后做 L2 归一化，再对 MatMul/Gemm 权重做 per-channel 动态 INT8 量化。最终模型约 54.6 MiB，移动端与预计算都使用这个文件。词表大小 21128，保留大小写及重音，最大输入 350 tokens。查询添加官方中文检索前缀，文档不添加。

分块总长度不超过 340 个 UTF-16 代码单元（含最多 100 字的标题），优先保留句子边界。该上界确保 BERT 输入在 350 tokens 内，脚本和单元测试另行逐块验证。原文偏移均为 UTF-16，适配 Android String。文档标识由去序号的路径和标题生成；块标识由文档标识和完整块文本生成。条目编号变化不会改变身份。

`model_manifest.json` 记录模型、词表、语料、词法索引、向量 SHA256 与索引版本；`vectors.bin` 为与 corpus.chunks 同序的 512 维 little-endian float32。预计算每次只传一个段落，避免动态量化批内范围影响手机一致性。

`lexical.json` 内置与文档同序的 BM25 词频，由资源脚本自动调用 `build_lexical.py` 生成，也可单独运行后者重建。`LexicalAssetTest` 将全部文档预计算词频与 Kotlin 实时分词结果严格比较。内容更新会先构建新词法表，与正文和向量一起保存及激活。

`AssetParityTest` 验证全部 Python / Kotlin 解析结果相同、全部块 token 限制、13 类 tokenizer golden、向量有限且归一化、资源哈希。`embedding_golden.json` 提供五个真实查询的向量与近邻，可用于 Android ONNX CPU 推理误差核验。`parser_source_fixture.json` 保存固定 SHA 的原始输入，支持完整离线解析回归。

脚本检测已有 model.onnx 时复用。需要重新导出模型时只删除 `app/src/main/assets/model.onnx` 后重跑；不得单独替换模型而保留旧 vectors.bin。
