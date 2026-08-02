package io.github.loredock.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.loredock.auth.config.McpAccessProperties;
import io.github.loredock.auth.config.McpWebConfiguration;
import io.github.loredock.knowledge.api.KnowledgeDocumentAccessService;
import io.github.loredock.knowledge.api.KnowledgeSearchService;
import io.github.loredock.knowledge.model.DocumentRevision;
import io.github.loredock.knowledge.model.command.CreateKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.knowledge.service.KnowledgeDocumentCommandService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class KnowledgeMcpControllerTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 业务目的：MCP 只能公开需求确认的查询与草稿提交工具，不得顺带暴露 Agent 内部 Tool 或发布能力；
     * 防止框架自动扫描扩大本地客户端权限。
     */
    @Test
    void exposesOnlyTheApprovedMcpToolSet() {
        Set<String> names = Arrays.stream(KnowledgeMcpController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
                "knowledge_directory_list", "knowledge_document_list", "knowledge_document_read",
                "knowledge_grep", "knowledge_search", "knowledge_draft_submit");
        System.out.println("MCP 工具测试证据：toolNames=" + names);
    }

    /**
     * 业务目的：写 Token 提交 Markdown 只能创建项目 DRAFT，并且返回中不出现 conversation 或 run；
     * 防止本地提交绕过网页勾选、AI 整理与人工审核边界。
     */
    @Test
    void submitsMarkdownAsOnePendingProjectDraftWithoutStartingAConversation() throws Exception {
        KnowledgeDocumentAccessService documents = mock(KnowledgeDocumentAccessService.class);
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        KnowledgeDocumentCommandService commands = mock(KnowledgeDocumentCommandService.class);
        ProjectService projects = mock(ProjectService.class);
        when(projects.resolveEnabledScope("atlas", null))
                .thenReturn(new ProjectScope(7L, "atlas", "Atlas", true, 11L, "main"));
        KnowledgeDocumentView view = mock(KnowledgeDocumentView.class);
        when(view.id()).thenReturn(91L);
        when(view.revision()).thenReturn(new DocumentRevision(1));
        when(view.status()).thenReturn(DocumentStatus.DRAFT);
        when(commands.create(any(CreateKnowledgeDocumentCommand.class))).thenReturn(view);
        authenticateWriteRequest();
        KnowledgeMcpController tools = new KnowledgeMcpController(documents, search, commands, projects);

        KnowledgeMcpController.DraftSubmitted result = tools.submitDraft(
                "atlas", "退款审批规则", "# 退款审批\n\n超过阈值需人工审核。", "业务规则", List.of("退款"), "refund.md");

        ArgumentCaptor<CreateKnowledgeDocumentCommand> command =
                ArgumentCaptor.forClass(CreateKnowledgeDocumentCommand.class);
        verify(commands).create(command.capture());
        assertThat(command.getValue().scope().projectId()).isEqualTo(7L);
        assertThat(command.getValue().body().value()).contains("人工审核");
        assertThat(result).isEqualTo(new KnowledgeMcpController.DraftSubmitted(91L, 1, "DRAFT"));
        assertThat(Arrays.stream(result.getClass().getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("conversationId", "runId");
        System.out.println("MCP 提交测试证据：documentId=" + result.documentId()
                + "，status=" + result.status() + "，conversationCreated=false");
    }

    private void authenticateWriteRequest() throws Exception {
        McpWebConfiguration authentication =
                new McpWebConfiguration(new McpAccessProperties("read-secret", "write-secret"));
        Method interceptorMethod = McpWebConfiguration.class.getDeclaredMethod("authenticationInterceptor");
        interceptorMethod.setAccessible(true);
        org.springframework.web.servlet.HandlerInterceptor interceptor =
                (org.springframework.web.servlet.HandlerInterceptor) interceptorMethod.invoke(authentication);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer write-secret");
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
