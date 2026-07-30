package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.application.AdminKnowledgeDocumentQuery;
import io.github.loredock.knowledge.application.AdminKnowledgeDocumentQueryUseCase;
import io.github.loredock.knowledge.application.ArchiveKnowledgeDocumentCommand;
import io.github.loredock.knowledge.application.CreateKnowledgeDocumentCommand;
import io.github.loredock.knowledge.application.EditKnowledgeDocumentCommand;
import io.github.loredock.knowledge.application.KnowledgeDocumentCommandUseCase;
import io.github.loredock.knowledge.application.KnowledgeDocumentLifecycleUseCase;
import io.github.loredock.knowledge.application.KnowledgeScopeInvalidException;
import io.github.loredock.knowledge.application.KnowledgeScopeResolver;
import io.github.loredock.knowledge.application.PublishKnowledgeDocumentCommand;
import io.github.loredock.knowledge.domain.DocumentBody;
import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentTags;
import io.github.loredock.knowledge.domain.DocumentTitle;
import io.github.loredock.knowledge.domain.KnowledgeScope;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 管理员知识文档读写与生命周期入口；角色授权由统一 `/api/admin/**` 服务端拦截链完成。
 */
@RestController
@RequestMapping(KnowledgeDocumentHttpContract.ADMIN_BASE_PATH)
public class AdminKnowledgeDocumentController {

    private final AdminKnowledgeDocumentQueryUseCase queries;
    private final KnowledgeDocumentCommandUseCase commands;
    private final KnowledgeDocumentLifecycleUseCase lifecycle;
    private final KnowledgeScopeResolver scopes;

    /**
     * @param queries 管理查询用例
     * @param commands 创建与编辑用例
     * @param lifecycle 发布与归档用例
     * @param scopes 管理范围解析端口
     */
    public AdminKnowledgeDocumentController(
            AdminKnowledgeDocumentQueryUseCase queries,
            KnowledgeDocumentCommandUseCase commands,
            KnowledgeDocumentLifecycleUseCase lifecycle,
            KnowledgeScopeResolver scopes
    ) {
        this.queries = queries;
        this.commands = commands;
        this.lifecycle = lifecycle;
        this.scopes = scopes;
    }

    /**
     * @return 符合明确筛选的管理摘要分页
     */
    @GetMapping
    public PageResponse<KnowledgeDocumentSummaryResponse> list(
            @RequestParam(required = false) KnowledgeScopeType scopeType,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) String directory,
            @RequestParam(required = false) io.github.loredock.knowledge.domain.DocumentStatus status,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        validateFilterScope(scopeType, projectId, branchId);
        return KnowledgeDocumentHttpMapper.toSummaryPage(queries.list(new AdminKnowledgeDocumentQuery(
                scopeType, projectId, branchId, directory == null ? null : new DocumentDirectory(directory),
                status, tag, page, size)));
    }

    /**
     * @param documentId 文档 UUID
     * @return 包含审计、替代和同步状态的管理详情
     */
    @GetMapping("/{documentId}")
    public AdminKnowledgeDocumentResponse get(@PathVariable UUID documentId) {
        return KnowledgeDocumentHttpMapper.toAdminResponse(queries.get(documentId));
    }

    /**
     * @param request 已完成基础字段校验的创建请求
     * @return revision 1 的新草稿
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminKnowledgeDocumentResponse create(@Valid @RequestBody KnowledgeDocumentWriteRequest request) {
        KnowledgeScope scope = resolveScope(request);
        return KnowledgeDocumentHttpMapper.toAdminResponse(commands.create(new CreateKnowledgeDocumentCommand(
                request.format(), new DocumentTitle(request.title()), new DocumentBody(request.body()),
                new DocumentDirectory(request.directory()), DocumentTags.of(request.tags()),
                source(request), scope)));
    }

    /**
     * @param documentId 文档 UUID
     * @param request 全量目标字段
     * @return 提交后的当前详情；同值请求不增加修订
     */
    @PutMapping("/{documentId}")
    public AdminKnowledgeDocumentResponse edit(
            @PathVariable UUID documentId,
            @Valid @RequestBody KnowledgeDocumentWriteRequest request
    ) {
        KnowledgeScope scope = resolveScope(request);
        return KnowledgeDocumentHttpMapper.toAdminResponse(commands.edit(new EditKnowledgeDocumentCommand(
                documentId, request.format(), new DocumentTitle(request.title()), new DocumentBody(request.body()),
                new DocumentDirectory(request.directory()), DocumentTags.of(request.tags()),
                source(request), scope)));
    }

    /**
     * @param documentId 待发布文档 UUID
     * @param request 可选替代目标
     * @return 提交后的发布详情
     */
    @PostMapping("/{documentId}/publish")
    public AdminKnowledgeDocumentResponse publish(
            @PathVariable UUID documentId,
            @RequestBody(required = false) PublishKnowledgeDocumentRequest request
    ) {
        UUID replacesId = request == null ? null : request.replacesDocumentId();
        return KnowledgeDocumentHttpMapper.toAdminResponse(
                lifecycle.publish(new PublishKnowledgeDocumentCommand(documentId, replacesId)));
    }

    /**
     * @param documentId 待归档文档 UUID
     * @return 提交后的归档详情
     */
    @PostMapping("/{documentId}/archive")
    public AdminKnowledgeDocumentResponse archive(@PathVariable UUID documentId) {
        return KnowledgeDocumentHttpMapper.toAdminResponse(
                lifecycle.archive(new ArchiveKnowledgeDocumentCommand(documentId)));
    }

    private KnowledgeScope resolveScope(KnowledgeDocumentWriteRequest request) {
        return scopes.resolveAdmin(request.scope().type(), request.scope().project(), request.scope().branch());
    }

    private DocumentSource source(KnowledgeDocumentWriteRequest request) {
        DocumentSourceRequest source = request.source();
        return new DocumentSource(
                source.type(), source.wikiUrl(), source.originalFilename(), source.curationNote());
    }

    private void validateFilterScope(KnowledgeScopeType type, UUID projectId, UUID branchId) {
        boolean valid = switch (type) {
            case null -> projectId == null && branchId == null;
            case GLOBAL -> projectId == null && branchId == null;
            case PROJECT -> projectId != null && branchId == null;
            case BRANCH -> projectId != null && branchId != null;
        };
        if (!valid) {
            throw new KnowledgeScopeInvalidException();
        }
    }
}
