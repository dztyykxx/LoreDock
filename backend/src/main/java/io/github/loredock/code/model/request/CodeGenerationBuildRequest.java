package io.github.loredock.code.model.request;

import io.github.loredock.code.model.CodeCommit;
import io.github.loredock.code.model.result.CodeGenerationFile;
import java.util.List;
import java.util.Objects;

/** 单次 generation 发布请求；generation ID 只能由服务端生成，文件路径全部是仓库逻辑路径。 */
public record CodeGenerationBuildRequest(
        Long generationId,
        Long projectId,
        Long branchId,
        Long snapshotId,
        String commit,
        List<CodeGenerationFile> files
) {
    /** 创建不可变且范围完整的发布请求。 */
    public CodeGenerationBuildRequest {
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        commit = new CodeCommit(commit).value();
        files = List.copyOf(Objects.requireNonNull(files, "files"));
    }
}
