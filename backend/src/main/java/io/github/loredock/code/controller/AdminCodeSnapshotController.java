package io.github.loredock.code.controller;

import io.github.loredock.code.model.command.UploadCodeSnapshotCommand;
import io.github.loredock.code.model.request.AdminCodeSnapshotQuery;
import io.github.loredock.code.model.response.CodeSnapshotAdminPageResponse;
import io.github.loredock.code.model.response.CodeSnapshotAdminResponse;
import io.github.loredock.code.model.response.CodeSnapshotJobResponse;
import io.github.loredock.code.model.result.CodeSnapshotAdminPage;
import io.github.loredock.code.model.result.CodeSnapshotAdminView;
import io.github.loredock.code.model.result.CodeSnapshotJobView;
import io.github.loredock.code.model.result.CodeSnapshotUpload;
import io.github.loredock.code.service.AdminCodeSnapshotQueryService;
import io.github.loredock.code.service.CodeSnapshotUploadService;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 管理员代码快照上传、分页和任务轮询入口；角色授权由统一 `/api/admin/**` 拦截链完成。 */
@RestController
@ConditionalOnProperty(prefix = "loredock.code", name = "enabled", havingValue = "true")
@RequestMapping("/api/admin")
public class AdminCodeSnapshotController {

    private final CodeSnapshotUploadService commands;
    private final AdminCodeSnapshotQueryService queries;

    /**
     * @param commands 上传与重建用例
     * @param queries 管理分页与任务查询用例
     */
    public AdminCodeSnapshotController(
            CodeSnapshotUploadService commands,
            AdminCodeSnapshotQueryService queries
    ) {
        this.commands = commands;
        this.queries = queries;
    }

    /**
     * @return 只表示对象、候选和任务已经持久化的 202 任务视图
     */
    @PostMapping("/code-snapshots")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CodeSnapshotJobResponse upload(
            @RequestParam Long projectId,
            @RequestParam Long branchId,
            @RequestParam String commit,
            @RequestParam("file") MultipartFile file
    ) {
        try (var input = file.getInputStream()) {
            return response(commands.upload(new UploadCodeSnapshotCommand(
                    projectId, branchId, commit,
                    new CodeSnapshotUpload(input, file.getOriginalFilename(), file.getContentType(), file.getSize()))));
        } catch (IOException failure) {
            throw new UncheckedIOException("unable to consume code snapshot upload", failure);
        }
    }

    /** @return 候选、失败、活动和已替换快照的稳定管理分页。 */
    @GetMapping("/code-snapshots")
    public CodeSnapshotAdminPageResponse list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        CodeSnapshotAdminPage result = queries.list(new AdminCodeSnapshotQuery(projectId, branchId, page, size));
        return new CodeSnapshotAdminPageResponse(
                result.items().stream().map(this::response).toList(),
                result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    /** @return 指定代码构建或重建任务的脱敏当前状态。 */
    @GetMapping("/code-snapshot-jobs/{jobId}")
    public CodeSnapshotJobResponse getJob(@PathVariable Long jobId) {
        return response(queries.getJob(jobId));
    }

    /** @return 当前活动快照新建重建任务的 202 受理状态。 */
    @PostMapping("/code-snapshots/{snapshotId}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CodeSnapshotJobResponse reindex(@PathVariable Long snapshotId) {
        return response(commands.reindex(snapshotId));
    }

    private CodeSnapshotAdminResponse response(CodeSnapshotAdminView view) {
        return new CodeSnapshotAdminResponse(
                view.snapshotId(), view.projectId(), view.branchId(), view.commit(), view.status(),
                view.indexedFileCount(), view.ignoredFileCount(), view.indexedAt(), view.createdAt(), view.updatedAt());
    }

    private CodeSnapshotJobResponse response(CodeSnapshotJobView view) {
        return new CodeSnapshotJobResponse(
                view.snapshotId(), view.jobId(), view.projectId(), view.branchId(), view.commit(),
                view.status(), view.progress(), view.indexedFileCount(), view.ignoredFileCount(),
                view.createdAt(), view.finishedAt(), view.failureCode(), view.failureSummary());
    }
}
