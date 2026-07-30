package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.CodeGenerationBuildRequest;

import java.io.IOException;
import java.nio.file.Path;

/** 可故障注入的关闭后重开验证边界。 */
@FunctionalInterface
public interface GenerationIndexValidator {
    /** 验证文档数、唯一路径及每个文档的固定业务范围。 */
    void validate(Path directory, CodeGenerationBuildRequest expected) throws IOException;
}
