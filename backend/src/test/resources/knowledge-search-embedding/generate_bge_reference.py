"""用 BGE 官方 Sentence-Transformers 路径生成公开中文参考向量；仅供开发期人工重建夹具。"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
from sentence_transformers import SentenceTransformer


MODEL_ID = "BAAI/bge-small-zh-v1.5"
MODEL_REVISION = "7999e1d3359715c523056ef9478215996d62a620"
QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："
QUERY = "怎样把场景配置保存成文件，之后还能恢复？"
PASSAGES = [
    "导出功能会把当前场景保存为可移植文件，稍后可以在导入页面恢复。",
    "导入前应先检查文件格式和版本，校验通过后再创建场景副本。",
    "项目启动时先复制环境变量示例，再准备本地数据库和对象存储目录。",
    "发布知识前需要管理员审核，草稿内容不会进入正式检索。",
    "归档文档会立即退出正式搜索结果，但仍保留历史审计记录。",
    "场景包中可以包含节点、连线、画布设置和公开的示例资源。",
    "若上传文件超过容量限制，系统会拒绝处理并返回明确错误。",
    "分支没有代码快照时，仍可查询该分支已经发布的人工知识。",
    "数据库迁移只能追加新版本，不能修改已经执行过的迁移脚本。",
    "搜索结果按项目和分支隔离，不会用其他分支的内容补齐结果。",
    "登录会话使用安全 Cookie，普通成员只能执行只读操作。",
    "后台重建失败时继续保留上一个已经激活的索引版本。",
]


def rounded(vector: np.ndarray) -> list[float]:
    return [round(float(value), 8) for value in vector]


def main() -> None:
    model = SentenceTransformer(MODEL_ID, revision=MODEL_REVISION, device="cpu")
    query_inputs = [QUERY, QUERY_INSTRUCTION + QUERY]
    query_vectors = model.encode(query_inputs, normalize_embeddings=True, convert_to_numpy=True)
    passage_vectors = model.encode(PASSAGES, normalize_embeddings=True, convert_to_numpy=True)
    scores = query_vectors[1] @ passage_vectors.T
    ranking = np.argsort(-scores, kind="stable").tolist()
    onnx_path = Path(".loredock-run/models/bge-small-zh-v1.5/model.onnx")
    model_checksum = hashlib.sha256(onnx_path.read_bytes()).hexdigest() if onnx_path.is_file() else None
    fixture = {
        "modelId": MODEL_ID,
        "modelRevision": MODEL_REVISION,
        "modelChecksum": model_checksum,
        "dimension": int(query_vectors.shape[1]),
        "pooling": "CLS",
        "normalized": True,
        "queryInstruction": QUERY_INSTRUCTION,
        "query": QUERY,
        "rawQueryVector": rounded(query_vectors[0]),
        "instructedQueryVector": rounded(query_vectors[1]),
        "passages": [
            {"id": f"public-zh-{index + 1:02d}", "text": text, "vector": rounded(vector)}
            for index, (text, vector) in enumerate(zip(PASSAGES, passage_vectors, strict=True))
        ],
        "expectedRanking": [f"public-zh-{index + 1:02d}" for index in ranking],
        "expectedScores": [round(float(scores[index]), 8) for index in ranking],
        "generatedBy": {
            "implementation": "sentence-transformers",
            "normalizeEmbeddings": True,
            "device": "cpu",
        },
    }
    output = Path("backend/src/test/resources/knowledge-search-embedding/bge-small-zh-v1.5-reference.json")
    output.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
