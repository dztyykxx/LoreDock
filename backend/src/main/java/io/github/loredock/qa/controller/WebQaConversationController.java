package io.github.loredock.qa.controller;

import io.github.loredock.agent.api.AgentService;
import io.github.loredock.auth.api.AuthService;
import io.github.loredock.auth.api.AuthenticatedActor;
import io.github.loredock.qa.api.QaService;
import io.github.loredock.qa.converter.WebQaHttpMapper;
import io.github.loredock.qa.model.response.WebQaConversationPageResponse;
import io.github.loredock.qa.model.response.WebQaConversationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前操作者在单个项目内的最近问答会话读取入口。 */
@RestController
@RequestMapping("/api/projects/{identifier}/qa/conversations")
public class WebQaConversationController {
    private final QaService questions;
    private final AgentService agents;
    private final AuthService sessions;

    /** @param questions 会话查询契约 @param agents 运行事件查询契约 @param sessions 当前认证身份 */
    public WebQaConversationController(QaService questions, AgentService agents, AuthService sessions) {
        this.questions = questions;
        this.agents = agents;
        this.sessions = sessions;
    }

    /** @return 当前操作者在项目中的最近会话 */
    @GetMapping
    public WebQaConversationPageResponse history(
            @PathVariable String identifier,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        QaService.ConversationPage page = questions.conversations(new QaService.ConversationHistoryQuery(
                actor.username(), identifier, cursor, limit));
        return new WebQaConversationPageResponse(
                page.items().stream().map(WebQaHttpMapper::toResponse).toList(), page.nextCursor());
    }

    /** @return 会话摘要与按稳定顺序排列的独立轮次 */
    @GetMapping("/{conversationId}")
    public WebQaConversationResponse detail(
            @PathVariable String identifier,
            @PathVariable Long conversationId
    ) {
        AuthenticatedActor actor = sessions.currentSession();
        QaService.Conversation conversation = questions.conversation(new QaService.ConversationDetailQuery(
                actor.username(), identifier, conversationId));
        var rounds = conversation.rounds().stream().map(round -> {
            long sequence = agents.lastEventSequence(round.runId(), actor.username());
            var events = agents.listEvents(round.runId(), actor.username(), 0, 200);
            return WebQaHttpMapper.toResponse(round, sequence, events);
        }).toList();
        return new WebQaConversationResponse(WebQaHttpMapper.toResponse(conversation.summary()), rounds);
    }
}
