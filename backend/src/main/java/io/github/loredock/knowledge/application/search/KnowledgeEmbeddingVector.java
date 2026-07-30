package io.github.loredock.knowledge.application.search;

import java.util.Objects;

/** 项目自有的不可变 Embedding 向量 DTO，避免框架或 ONNX 类型泄漏到应用层。 */
public final class KnowledgeEmbeddingVector {
    private final float[] values;

    /**
     * @param values 模型输出向量；构造时复制，禁止后续调用方改写
     */
    public KnowledgeEmbeddingVector(float[] values) {
        this.values = Objects.requireNonNull(values, "embedding values are required").clone();
    }

    /**
     * @return 向量值的副本
     */
    public float[] values() {
        return values.clone();
    }

    /**
     * @return 向量维度
     */
    public int dimension() {
        return values.length;
    }
}
