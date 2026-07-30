package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.CodeGenerationBuildRequest;
import io.github.loredock.code.application.CodeGenerationFileProducer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 可故障注入的 Lucene generation 写入边界。 */
@FunctionalInterface
public interface GenerationIndexWriter {
    /** 将全部文档写入并关闭目录。 */
    void write(Path directory, List<CodeIndexDocument> documents) throws IOException;

    /**
     * 逐文件写入并关闭目录；默认实现仅用于故障注入适配器，生产 Lucene writer 会覆盖为真实流式写入。
     */
    default GenerationIndexWriteSummary writeStreaming(
            Path directory,
            CodeGenerationBuildRequest scope,
            CodeGenerationFileProducer producer
    ) throws IOException {
        List<CodeIndexDocument> documents = new ArrayList<>();
        producer.produce(file -> documents.add(new CodeIndexDocument(
                scope.projectId(), scope.branchId(), scope.snapshotId(), scope.generationId(),
                scope.commit(), file.path(), file.language(), file.content())));
        write(directory, documents);
        return new GenerationIndexWriteSummary(documents.stream().map(CodeIndexDocument::path).toList());
    }
}
