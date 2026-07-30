package io.github.loredock.code.application;

import io.github.loredock.job.application.JobExecutionContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 构建与重建共用的 ZIP 安全读取、文件选择和 Lucene generation 发布流水线。 */
@Service
public class CodeSnapshotGenerationBuilder {

    private final CodeArchiveReadPort archives;
    private final CodeFileSelector selector;
    private final CodeGenerationPublishPort publisher;

    /**
     * @param archives ZIP 安全读取端口
     * @param selector 文件安全选择端口
     * @param publisher generation 原子发布端口
     */
    public CodeSnapshotGenerationBuilder(
            CodeArchiveReadPort archives,
            CodeFileSelector selector,
            CodeGenerationPublishPort publisher
    ) {
        this.archives = archives;
        this.selector = selector;
        this.publisher = publisher;
    }

    /** 发布固定范围的新 generation，返回索引与忽略计数。 */
    public CodeSnapshotGenerationResult build(
            JobExecutionContext context,
            CodeSnapshotRecord snapshot,
            UUID generationId
    ) {
        context.updateProgress(5);
        context.heartbeat();
        long[] ignored = {0};
        PublishedCodeGeneration published = publisher.publishStreaming(new CodeGenerationBuildRequest(
                generationId, snapshot.projectId(), snapshot.branchId(), snapshot.id(), snapshot.commit(), List.of()),
                consumer -> archives.read(context.jobId(), snapshot.inputObjectKey(), (entry, input) -> {
                    CodeFileSelection selection = selector.select(entry, input);
                    if (selection.selected()) {
                        // 当前完整正文由 selector 按单文件上限持有，写入返回后即可释放，不聚合整个仓库内容。
                        consumer.accept(new CodeGenerationFile(
                                selection.path(), language(selection.path()), selection.text()));
                    } else {
                        ignored[0]++;
                    }
                    context.heartbeat();
                }));
        context.updateProgress(85);
        context.heartbeat();
        return new CodeSnapshotGenerationResult(published.documentCount(), ignored[0]);
    }

    private String language(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 || dot == lower.length() - 1 ? "text" : lower.substring(dot + 1);
    }
}
