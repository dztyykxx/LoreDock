package io.github.loredock.code.application;

import java.util.UUID;

/** 已关闭、重新打开验证且完成原子目录发布的 generation 结果，不暴露物理目录。 */
public record PublishedCodeGeneration(UUID generationId, long documentCount) {
}
