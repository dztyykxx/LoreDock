package io.github.loredock.knowledge.controller;

import io.github.loredock.knowledge.converter.KnowledgeDocumentHttpContract;
import io.github.loredock.knowledge.converter.KnowledgeDocumentHttpMapper;
import io.github.loredock.knowledge.exception.KnowledgeScopeInvalidException;
import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.command.ArchiveKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.command.BatchPublishKnowledgeDocumentsCommand;
import io.github.loredock.knowledge.model.command.CreateKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.command.EditKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.command.PublishKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.request.AdminBrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.model.request.AdminKnowledgeDocumentQuery;
import io.github.loredock.knowledge.model.request.BatchPublishKnowledgeDocumentsRequest;
import io.github.loredock.knowledge.model.request.DocumentSourceRequest;
import io.github.loredock.knowledge.model.request.KnowledgeDocumentWriteRequest;
import io.github.loredock.knowledge.model.request.PublishKnowledgeDocumentRequest;
import io.github.loredock.knowledge.model.response.AdminKnowledgeDocumentResponse;
import io.github.loredock.knowledge.model.response.BatchPublishKnowledgeDocumentsResponse;
import io.github.loredock.knowledge.model.response.KnowledgeBrowseResponse;
import io.github.loredock.knowledge.model.response.KnowledgeDocumentSummaryResponse;
import io.github.loredock.knowledge.model.response.PageResponse;
import io.github.loredock.knowledge.service.KnowledgeDocumentCommandService;
import io.github.loredock.knowledge.service.KnowledgeDocumentLifecycleService;
import io.github.loredock.knowledge.service.KnowledgeDocumentQueryService;
import io.github.loredock.knowledge.service.project.ProjectKnowledgeScopeResolver;
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

/**
 * 管理员知识文档读写与生命周期入口；角色授权由统一 `/api/admin/**` 服务端拦截链完成。
 */
@RestController
@RequestMapping(KnowledgeDocumentHttpContract.ADMIN_BASE_PATH)
public class AdminKnowledgeDocumentController {

    private final KnowledgeDocumentQueryService queries;
    private final KnowledgeDocumentCommandService commands;
    private final KnowledgeDocumentLifecycleService lifecycle;
    private final ProjectKnowledgeScopeResolver scopes;

    /**
     * @param queries 管理查询用例
     * @param commands 创建与编辑用例
     * @param lifecycle 发布与归档用例
     * @param scopes 管理范围解析端口
     */
    public AdminKnowledgeDocumentController(
            KnowledgeDocumentQueryService queries,
            KnowledgeDocumentCommandService commands,
            KnowledgeDocumentLifecycleService lifecycle,
            ProjectKnowledgeScopeResolver scopes
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
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) String directory,
            @RequestParam(required = false) io.github.loredock.knowledge.model.enums.DocumentStatus status,
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
     * 管理员知识工作区上下文浏览；目录统计与文档分页使用相同生命周期过滤条件。
     *
     * @param context 明确通用或项目上下文
     * @param project 项目标识；通用上下文必须为空
     * @param directory 可选目录子树
     * @param status 可选生命周期状态
     * @param excludeGlobal 项目草稿池是否排除通用文档；项目草稿列表使用
     * @param page 零基页码
     * @param size 页容量
     * @return 完整目录树与当前子树摘要页
     */
    @GetMapping("/browse")
    public KnowledgeBrowseResponse browse(
            @RequestParam io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType context,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String directory,
            @RequestParam(required = false) io.github.loredock.knowledge.model.enums.DocumentStatus status,
            @RequestParam(defaultValue = "false") boolean excludeGlobal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var resolved = scopes.resolveBrowse(context, project, null, excludeGlobal);
        return KnowledgeDocumentHttpMapper.toBrowseResponse(queries.browseAdmin(
                new AdminBrowseKnowledgeDocumentsQuery(
                        resolved, directory == null ? null : new DocumentDirectory(directory), status, page, size)));
    }

    /**
     * @param documentId 文档 Long
     * @return 包含审计、替代和同步状态的管理详情
     */
    @GetMapping("/{documentId}")
    public AdminKnowledgeDocumentResponse get(@PathVariable Long documentId) {
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
     * @param documentId 文档 Long
     * @param request 全量目标字段
     * @return 提交后的当前详情；同值请求不增加修订
     */
    @PutMapping("/{documentId}")
    public AdminKnowledgeDocumentResponse edit(
            @PathVariable Long documentId,
            @Valid @RequestBody KnowledgeDocumentWriteRequest request
    ) {
        KnowledgeScope scope = resolveScope(request);
        return KnowledgeDocumentHttpMapper.toAdminResponse(commands.edit(new EditKnowledgeDocumentCommand(
                documentId, request.format(), new DocumentTitle(request.title()), new DocumentBody(request.body()),
                new DocumentDirectory(request.directory()), DocumentTags.of(request.tags()),
                source(request), scope)));
    }

    /**
     * @param documentId 待发布文档 Long
     * @param request 可选替代目标
     * @return 提交后的发布详情
     */
    @PostMapping("/{documentId}/publish")
    public AdminKnowledgeDocumentResponse publish(
            @PathVariable Long documentId,
            @RequestBody(required = false) PublishKnowledgeDocumentRequest request
    ) {
        Long replacesId = request == null ? null : request.replacesDocumentId();
        return KnowledgeDocumentHttpMapper.toAdminResponse(
                lifecycle.publish(new PublishKnowledgeDocumentCommand(documentId, replacesId)));
    }

    /**
     * 原子发布管理员在当前页显式勾选的文档；替代关系和重新索引仍使用独立操作。
     *
     * @param request 一至一百个唯一文档标识
     * @return 新发布与幂等已发布数量
     */
    @PostMapping("/batch-publish")
    public BatchPublishKnowledgeDocumentsResponse publishBatch(
            @Valid @RequestBody BatchPublishKnowledgeDocumentsRequest request
    ) {
        return KnowledgeDocumentHttpMapper.toBatchPublishResponse(lifecycle.publishBatch(
                new BatchPublishKnowledgeDocumentsCommand(request.documentIds())));
    }

    /**
     * @param documentId 待归档文档 Long
     * @return 提交后的归档详情
     */
    @PostMapping("/{documentId}/archive")
    public AdminKnowledgeDocumentResponse archive(@PathVariable Long documentId) {
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

    private void validateFilterScope(KnowledgeScopeType type, Long projectId, Long branchId) {
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
