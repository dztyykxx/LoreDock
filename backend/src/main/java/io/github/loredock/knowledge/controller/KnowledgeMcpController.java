package io.github.loredock.knowledge.controller;

import io.github.loredock.platform.web.McpRequestAccess;
import io.github.loredock.knowledge.api.KnowledgeDocumentAccessService;
import io.github.loredock.knowledge.api.KnowledgeMatches;
import io.github.loredock.knowledge.api.KnowledgeQuery;
import io.github.loredock.knowledge.api.KnowledgeSearchService;
import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.command.CreateKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.service.KnowledgeDocumentCommandService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.util.List;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 本地 Agent 的 MCP 薄入口。查询只读取已发布知识；写入只创建项目待处理草稿，
 * 不创建知识任务、不调用模型，也不暴露发布能力。
 */
@Component
public class KnowledgeMcpController {

    private final KnowledgeDocumentAccessService documents;
    private final KnowledgeSearchService search;
    private final KnowledgeDocumentCommandService commands;
    private final ProjectService projects;

    /** 注入 Web、内部 Agent 已使用的同一知识 Service。 */
    public KnowledgeMcpController(
            KnowledgeDocumentAccessService documents,
            KnowledgeSearchService search,
            KnowledgeDocumentCommandService commands,
            ProjectService projects
    ) {
        this.documents = documents;
        this.search = search;
        this.commands = commands;
        this.projects = projects;
    }

    /** @return 当前项目与通用范围的已发布知识目录 */
    @McpTool(name = "knowledge_directory_list", description = "列出项目及通用范围内的已发布知识目录",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<KnowledgeDocumentAccessService.DirectoryEntry> listDirectories(
            @McpToolParam(description = "项目标识，例如 network-designer") String project,
            @McpToolParam(required = false, description = "可选目录前缀") String prefix,
            @McpToolParam(required = false, description = "返回上限，最大 100") Integer limit
    ) {
        return documents.listPublishedDirectories(project, prefix, bounded(limit, 50, 100));
    }

    /** @return 指定目录下的已发布知识摘要 */
    @McpTool(name = "knowledge_document_list", description = "列出指定目录下的已发布知识文档",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<KnowledgeDocumentAccessService.DocumentSummary> listDocuments(
            @McpToolParam(description = "项目标识") String project,
            @McpToolParam(required = false, description = "可选逻辑目录") String directory,
            @McpToolParam(required = false, description = "返回上限，最大 100") Integer limit
    ) {
        return documents.listPublishedDocuments(project, directory, bounded(limit, 50, 100));
    }

    /** @return 已发布文档的有界 Markdown 分段 */
    @McpTool(name = "knowledge_document_read", description = "按 Unicode 码点游标分段读取已发布知识文档",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public KnowledgeDocumentAccessService.DocumentPage readDocument(
            @McpToolParam(description = "项目标识") String project,
            @McpToolParam(description = "文档 ID") Long documentId,
            @McpToolParam(required = false, description = "起始 Unicode 码点游标，默认 0") Integer cursor,
            @McpToolParam(required = false, description = "本次最大返回码点数，默认 8000，最大 12000") Integer maxCodePoints
    ) {
        return documents.readPublishedPage(project, documentId, cursor, maxCodePoints);
    }

    /** @return 已发布知识的关键词匹配 */
    @McpTool(name = "knowledge_grep", description = "在已发布知识正文中执行大小写不敏感的关键词匹配",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<KnowledgeDocumentAccessService.KeywordMatch> grep(
            @McpToolParam(description = "项目标识") String project,
            @McpToolParam(description = "要匹配的关键词") String keyword,
            @McpToolParam(required = false, description = "可选逻辑目录") String directory,
            @McpToolParam(required = false, description = "可选文档 ID 列表") List<Long> documentIds,
            @McpToolParam(required = false, description = "命中上限，最大 50") Integer limit,
            @McpToolParam(required = false, description = "上下文行数，最大 3") Integer contextLines
    ) {
        return documents.grepPublished(project, keyword, directory,
                documentIds == null ? List.of() : documentIds, bounded(limit, 20, 50), bounded(contextLines, 1, 3));
    }

    /** @return 与 Web 问答同源索引中的近似知识结果 */
    @McpTool(name = "knowledge_search", description = "在项目及通用范围内近似检索已发布业务知识",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public KnowledgeMatches search(
            @McpToolParam(description = "项目标识") String project,
            @McpToolParam(description = "自然语言查询") String query,
            @McpToolParam(required = false, description = "返回上限，最大 20") Integer limit
    ) {
        ProjectScope scope = projects.resolveEnabledScope(project, null);
        Long version = search.findActiveIndexVersionId().orElse(null);
        if (version == null) {
            return new KnowledgeMatches(List.of(), List.of());
        }
        return search.search(new KnowledgeQuery(scope.projectIdentifier(), scope.branchName(), query,
                bounded(limit, 10, 20), version));
    }

    /** @return 新建待处理草稿的稳定标识；不会启动 AI 整理 */
    @McpTool(name = "knowledge_draft_submit", description = "向项目待处理草稿池提交一份 Markdown，不启动 AI、不发布",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    public DraftSubmitted submitDraft(
            @McpToolParam(description = "项目标识") String project,
            @McpToolParam(description = "业务知识标题") String title,
            @McpToolParam(description = "仅包含业务知识的 Markdown 正文") String markdown,
            @McpToolParam(required = false, description = "可选逻辑目录") String directory,
            @McpToolParam(required = false, description = "可选标签") List<String> tags,
            @McpToolParam(required = false, description = "本地来源文件名") String originalFilename
    ) {
        McpRequestAccess.requireWrite();
        ProjectScope projectScope = projects.resolveEnabledScope(project, null);
        String sourceName = originalFilename == null || originalFilename.isBlank()
                ? "mcp-business-knowledge.md" : originalFilename;
        var created = commands.create(new CreateKnowledgeDocumentCommand(
                DocumentFormat.MARKDOWN, new DocumentTitle(title), new DocumentBody(markdown),
                new DocumentDirectory(directory), DocumentTags.of(tags == null ? List.of() : tags),
                new DocumentSource(DocumentSourceType.UPLOAD, null, sourceName, "由本地 Agent 通过 MCP 提交"),
                KnowledgeScope.project(projectScope.projectId())));
        return new DraftSubmitted(created.id(), created.revision().value(), created.status().name());
    }

    private int bounded(Integer value, int defaultValue, int maximum) {
        return value == null ? defaultValue : Math.min(Math.max(value, 1), maximum);
    }

    /** MCP 草稿提交结果，不包含 conversation 或 run。 */
    public record DraftSubmitted(Long documentId, long revision, String status) {
    }
}
