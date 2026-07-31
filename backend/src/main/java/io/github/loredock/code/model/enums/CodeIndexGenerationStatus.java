package io.github.loredock.code.model.enums;

/** Lucene generation 生命周期；BUILDING 与 FAILED generation 永远不可被普通查询读取。 */
public enum CodeIndexGenerationStatus {
    BUILDING,
    ACTIVE,
    RETIRED,
    FAILED
}
