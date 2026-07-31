package io.github.loredock.qa.service;

import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.agent.api.AgentRunNotFoundException;
import io.github.loredock.agent.api.AgentService;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.qa.converter.WebQaCursorCodec;
import io.github.loredock.qa.exception.WebQaQuestionNotFoundException;
import io.github.loredock.qa.model.command.QueryWebQaDetailCommand;
import io.github.loredock.qa.model.command.QueryWebQaHistoryCommand;
import io.github.loredock.qa.model.enums.WebQaTrustState;
import io.github.loredock.qa.model.result.WebQaQuestionPage;
import io.github.loredock.qa.model.result.WebQaQuestionRecord;
import io.github.loredock.qa.model.result.WebQaStreamTarget;
import io.github.loredock.qa.model.snapshot.WebQaCursor;
import io.github.loredock.qa.model.snapshot.WebQaQuestionSnapshot;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 从受范围约束的问答记录和最新 Agent 数据库快照组装历史与详情；投影失败不覆盖终态事实。
 */
@Service
@Slf4j
public class QueryWebQaQuestionService {
    private static final int MAX_PAGE_SIZE = 100;
    private final ProjectService projects;
    private final AgentService agents;
    private final WebQaQuestionDataService questions;
    private final WebQaMessageDataService messages;
    private final DefaultWebQaAssistantMessageMaterializer materializer;

    /**
     * @param projects 启用项目访问复核
     * @param agents Agent 终态与引用查询契约
     * @param questions 问答身份仓储
     * @param messages 问答消息仓储
     * @param materializer 终态助手消息自愈投影器
     */
    public QueryWebQaQuestionService(
            ProjectService projects,
            AgentService agents,
            WebQaQuestionDataService questions,
            WebQaMessageDataService messages,
            DefaultWebQaAssistantMessageMaterializer materializer
    ) {
        this.projects = projects;
        this.agents = agents;
        this.questions = questions;
        this.messages = messages;
        this.materializer = materializer;
    }

    public WebQaQuestionPage history(QueryWebQaHistoryCommand command) {
        requireIdentity(command.operatorId(), command.projectIdentifier());
        if (command.limit() < 1 || command.limit() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("web QA page size out of range");
        }
        ProjectScope project = enabledProject(command.projectIdentifier());
        WebQaCursor after = command.cursor() == null || command.cursor().isBlank()
                ? null : WebQaCursorCodec.decode(command.cursor());
        List<WebQaQuestionRecord> records = questions.findHistory(
                command.operatorId(), project.projectId(), after, command.limit() + 1);
        boolean hasMore = records.size() > command.limit();
        List<WebQaQuestionRecord> visible = hasMore
                ? records.subList(0, command.limit()) : records;
        List<WebQaQuestionSnapshot> snapshots = new ArrayList<>(visible.size());
        for (WebQaQuestionRecord question : visible) {
            AgentRun run = agents.get(question.runId(), command.operatorId());
            snapshots.add(snapshot(question, run));
        }
        String nextCursor = hasMore && !visible.isEmpty()
                ? WebQaCursorCodec.encode(new WebQaCursor(
                        visible.getLast().createdAt(), visible.getLast().id()))
                : null;
        log.info("web_qa history queried traceId={} project={} resultCount={} hasMore={}",
                traceId(), project.projectIdentifier(), snapshots.size(), hasMore);
        return new WebQaQuestionPage(snapshots, nextCursor);
    }

    public WebQaQuestionSnapshot detail(QueryWebQaDetailCommand command) {
        WebQaStreamTarget target = authorize(command);
        WebQaQuestionRecord question = target.question();
        AgentRun run = target.run();
        WebQaQuestionSnapshot result = snapshot(question, run);
        log.info("web_qa detail queried traceId={} questionId={} runId={} project={} branch={} status={}",
                traceId(), question.id(), run.runId(), question.projectIdentifier(), question.branch(), run.status());
        return result;
    }

    public WebQaStreamTarget authorize(QueryWebQaDetailCommand command) {
        requireIdentity(command.operatorId(), command.projectIdentifier());
        if (command.questionId() == null) {
            throw new WebQaQuestionNotFoundException();
        }
        ProjectScope project = enabledProject(command.projectIdentifier());
        WebQaQuestionRecord question = questions.findVisibleById(
                        command.operatorId(), project.projectId(), command.questionId())
                .orElseThrow(WebQaQuestionNotFoundException::new);
        AgentRun run;
        try {
            run = agents.get(question.runId(), command.operatorId());
        } catch (AgentRunNotFoundException exception) {
            throw new WebQaQuestionNotFoundException();
        }
        return new WebQaStreamTarget(question, run);
    }

    private WebQaQuestionSnapshot snapshot(WebQaQuestionRecord question, AgentRun run) {
        try {
            materializer.materialize(question, run);
        } catch (RuntimeException exception) {
            // Agent 终态始终是事实来源；投影短暂失败不能把正确回答变成接口失败，下次读取会再次尝试。
            log.warn("web_qa assistant projection deferred traceId={} questionId={} runId={} "
                            + "errorCode=WEB_QA_MESSAGE_PROJECTION_FAILED",
                    traceId(), question.id(), run.runId());
        }
        return new WebQaQuestionSnapshot(
                question, run,
                WebQaTrustState.from(run.status(), run.resultType(), run.refusalReason(), run.errorCode()),
                messages.findByQuestionId(question.id()));
    }

    private ProjectScope enabledProject(String identifier) {
        try {
            return projects.resolveEnabledScope(identifier, null);
        } catch (RuntimeException exception) {
            throw new WebQaQuestionNotFoundException();
        }
    }

    private void requireIdentity(String operatorId, String projectIdentifier) {
        if (operatorId == null || operatorId.isBlank()
                || projectIdentifier == null || projectIdentifier.isBlank()) {
            throw new WebQaQuestionNotFoundException();
        }
    }

    private String traceId() {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? "background" : current;
    }
}
