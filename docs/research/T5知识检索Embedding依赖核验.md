# T5 知识检索 Embedding 依赖核验

## 1. 结论

T5 使用 `com.microsoft.onnxruntime:onnxruntime:1.28.0` 与
`ai.djl.huggingface:tokenizers:0.36.0`，直接读取锁定 BGE ONNX 导出物的
`sentence_embedding`。不使用 Spring AI 2.0.0 `TransformersEmbeddingModel`，不在 Java
中重写 tokenizer、pooling 或归一化，也不允许运行时从公网下载模型。

核验日期为 2026-07-30。版本来源为 Maven Central 元数据与项目正式发布页：

- [ONNX Runtime Maven Central](https://central.sonatype.com/artifact/com.microsoft.onnxruntime/onnxruntime/1.28.0)
- [ONNX Runtime v1.28.0](https://github.com/microsoft/onnxruntime/releases/tag/v1.28.0)
- [DJL tokenizer Maven Central](https://central.sonatype.com/artifact/ai.djl.huggingface/tokenizers/0.36.0)
- [DJL v0.36.0](https://github.com/deepjavalibrary/djl/releases/tag/v0.36.0)

## 2. Spring AI 不兼容证据

Spring AI 2.0.0 源码包中的 `TransformersEmbeddingModel` 把模型输出强制转换为三维
`float[][][]`，随后固定执行 attention-mask mean pooling；对应源码的 343～352 行与
396～412 行没有 L2 归一化。BGE `bge-small-zh-v1.5` 官方 Sentence-Transformers 参考则使用
CLS pooling 与 L2 归一化，因此该实现不能通过替换输出节点来保持模型语义。

隔离 PoC 对同一公开中文查询得到：

- 官方导出物 `sentence_embedding` 与 Sentence-Transformers 参考最大绝对误差：`2.32e-7`；
- Spring AI 固定 mean pooling 输出范数：`9.1581`；
- Spring AI 输出与官方句向量余弦：`0.7371`；
- Spring AI 输出与参考向量最大绝对误差：`3.31436`。

因此适配器只消费二维 `sentence_embedding` 并验证 512 维、有限值和单位范数，不在 Java
侧补写模型算法。

## 3. 版本、依赖与许可证

锁定后的 Maven 依赖树为：

```text
io.github.loredock:loredock-backend:0.1.0-SNAPSHOT
+- com.microsoft.onnxruntime:onnxruntime:1.28.0
\- ai.djl.huggingface:tokenizers:0.36.0
   \- ai.djl:api:0.36.0
      \- com.google.code.gson:gson:2.13.2
         \- com.google.errorprone:error_prone_annotations:2.41.0
```

- ONNX Runtime 使用 MIT License，见[官方许可证](https://github.com/microsoft/onnxruntime/blob/v1.28.0/LICENSE)。
- DJL 使用 Apache License 2.0，见[官方许可证](https://github.com/deepjavalibrary/djl/blob/v0.36.0/LICENSE)。
- `tokenizers-0.36.0.jar` 内含 Linux aarch64/x86_64、macOS aarch64 和 Windows x86_64
  CPU 原生库；从本地 `tokenizer.json` 创建实例不需要下载推理引擎。
- 本次未引入 DJL 模型引擎、Spring AI Transformers、PyTorch 或远程模型客户端。

## 4. 安全与兼容性核验

- 检查 ONNX Runtime v1.28.0 正式发布说明中的 Security Fixes，并选择包含这些修复的当前正式版。
- 以完整 Maven 坐标和锁定版本查询 OSV API，`onnxruntime:1.28.0` 与
  `tokenizers:0.36.0` 在 2026-07-30 均返回空漏洞集合。此结果只代表核验时已公开记录，后续
  依赖升级和发布门禁仍需复查。
- Java 21、macOS arm64 CPU 实测完成编译与离线加载；DJL 正确选择随 JAR 提供的
  `osx-aarch64` tokenizer 原生库，ONNX Runtime 成功加载锁定模型。
- 12 条公开中文 passage 的批量结果逐向量匹配官方参考，查询重复调用稳定，Top-1 为
  `public-zh-01`；一次实测初始化约 4.3 秒，预热单查询约 3 毫秒。该数值是开发机证据，正式
  性能结论以后续固定目标 CPU 的全量基准为准。

## 5. 锁定模型契约

- 模型：`BAAI/bge-small-zh-v1.5`
- revision：`7999e1d3359715c523056ef9478215996d62a620`
- 输出：`sentence_embedding`
- 维度：512
- pooling：CLS（由导出图完成）
- normalization：L2（由导出图完成）
- 查询指令：`为这个句子生成表示以用于检索相关文章：`
- 模型 SHA-256：`3a40c6eab3abdf2bd07651031a36038c2dfaf4ebb8d62ddc78f2324b2ff4389a`

模型与 tokenizer 只允许通过 `file:` 或 `classpath:` URI 提供。适配器首次语义调用时惰性读取、
校验 SHA-256 并初始化；HTTP(S)、缺失文件和 checksum 不符都返回稳定的 Embedding 不可用语义，
且日志不记录绝对路径、正文或向量。
