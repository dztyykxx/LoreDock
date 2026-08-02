package io.github.loredock.agent.controller;

import io.github.loredock.agent.api.KnowledgeTaskService;
import io.github.loredock.agent.service.KnowledgeTaskSseService;
import io.github.loredock.auth.api.AuthService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 管理员知识任务持久化事件游标 SSE。 */
@RestController
@RequestMapping("/api/admin/projects/{identifier}/knowledge-tasks/{conversationId}/events")
public class AdminKnowledgeTaskSseController {
    private final KnowledgeTaskService tasks;
    private final KnowledgeTaskSseService streams;
    private final AuthService sessions;

    public AdminKnowledgeTaskSseController(
            KnowledgeTaskService tasks,
            KnowledgeTaskSseService streams,
            AuthService sessions
    ) {
        this.tasks = tasks;
        this.streams = streams;
        this.sessions = sessions;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String identifier,
            @PathVariable Long conversationId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @RequestParam(required = false) Long after
    ) {
        String operator = sessions.currentSession().username();
        KnowledgeTaskService.KnowledgeTask task = tasks.get(conversationId, operator);
        if (!identifier.equals(task.projectIdentifier())) {
            throw new IllegalArgumentException("知识任务项目范围不匹配");
        }
        long cursor = cursor(lastEventId, after);
        return streams.open(new KnowledgeTaskSseService.StreamRequest(
                operator, identifier, conversationId, cursor, sessions.capture()));
    }

    private long cursor(String lastEventId, Long after) {
        String value = lastEventId == null || lastEventId.isBlank()
                ? (after == null ? "0" : after.toString()) : lastEventId.strip();
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new IllegalArgumentException("知识任务事件游标无效");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("知识任务事件游标无效", exception);
        }
    }
}
