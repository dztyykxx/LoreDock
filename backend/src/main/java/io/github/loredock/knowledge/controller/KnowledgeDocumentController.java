package io.github.loredock.knowledge.controller;

import io.github.loredock.knowledge.converter.KnowledgeDocumentHttpContract;
import io.github.loredock.knowledge.converter.KnowledgeDocumentHttpMapper;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.request.BrowseKnowledgeDocumentsQuery;
import io.github.loredock.knowledge.model.request.ReadKnowledgeDocumentQuery;
import io.github.loredock.knowledge.model.response.KnowledgeBrowseResponse;
import io.github.loredock.knowledge.model.response.KnowledgeDocumentResponse;
import io.github.loredock.knowledge.model.snapshot.KnowledgeBrowseContext;
import io.github.loredock.knowledge.service.KnowledgeDocumentQueryService;
import io.github.loredock.knowledge.service.project.ProjectKnowledgeScopeResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已登录成员与管理员共用的普通知识只读入口，只委托已执行查询前置隔离的应用用例。
 */
@RestController
@RequestMapping(KnowledgeDocumentHttpContract.PUBLIC_BASE_PATH)
public class KnowledgeDocumentController {

    private final KnowledgeDocumentQueryService queries;
    private final ProjectKnowledgeScopeResolver scopes;

    /**
     * @param queries 普通查询用例
     * @param scopes 项目主数据范围解析端口
     */
    public KnowledgeDocumentController(
            KnowledgeDocumentQueryService queries,
            ProjectKnowledgeScopeResolver scopes
    ) {
        this.queries = queries;
        this.scopes = scopes;
    }

    /**
     * @param context 明确 GLOBAL 或 PROJECT 入口
     * @param project 项目业务标识
     * @param branch 可选分支名，空值由项目能力解析 main
     * @param directory 可选逻辑目录；缺省表示全部文档，显式空字符串表示根目录
     * @param page 零基页码
     * @param size 页容量
     * @return 当前上下文目录与已发布摘要
     */
    @GetMapping
    public KnowledgeBrowseResponse browse(
            @RequestParam KnowledgeBrowseContextType context,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String branch,
            @RequestParam(required = false) String directory,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        KnowledgeBrowseContext resolved = scopes.resolveBrowse(context, project, branch);
        return KnowledgeDocumentHttpMapper.toBrowseResponse(queries.browse(
                new BrowseKnowledgeDocumentsQuery(
                        resolved, directory == null ? null : new DocumentDirectory(directory), page, size)));
    }

    /**
     * @param documentId 文档 Long
     * @param context 明确 GLOBAL 或 PROJECT 入口
     * @param project 项目业务标识
     * @param branch 可选分支名
     * @return 当前上下文仍可见的已发布详情
     */
    @GetMapping("/{documentId}")
    public KnowledgeDocumentResponse get(
            @PathVariable Long documentId,
            @RequestParam KnowledgeBrowseContextType context,
            @RequestParam(required = false) String project,
            @RequestParam(required = false) String branch
    ) {
        KnowledgeBrowseContext resolved = scopes.resolveBrowse(context, project, branch);
        return KnowledgeDocumentHttpMapper.toPublicResponse(
                queries.get(new ReadKnowledgeDocumentQuery(resolved, documentId)));
    }
}
