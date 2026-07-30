package io.github.loredock.qa.application;

import io.github.loredock.agent.application.AgentRunQueryUseCase;
import io.github.loredock.agent.application.AgentRunSnapshot;
import io.github.loredock.project.application.ProjectDetailView;
import io.github.loredock.project.application.ProjectQueryUseCase;
import io.github.loredock.qa.domain.WebQaCursor;
import io.github.loredock.qa.domain.WebQaCursorCodec;
import io.github.loredock.qa.domain.WebQaTrustState;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 从受范围约束的问答记录和最新 Agent 数据库快照组装历史与详情；投影失败不覆盖终态事实。
 */
@Service
@Slf4j
public class QueryWebQaQuestionService implements QueryWebQaQuestionUseCase {
    private static final int MAX_PAGE_SIZE = 100;
    private final ProjectQueryUseCase projects;
    private final AgentRunQueryUseCase runs;
    private final WebQaQuestionRepository questions;
    private final WebQaMessageRepository messages;
    private final WebQaAssistantMessageMaterializer materializer;

    /**
     * @param projects 启用项目访问复核
     * @param runs Agent 终态与引用查询
     * @param questions 问答身份仓储
     * @param messages 问答消息仓储
     * @param materializer 终态助手消息自愈投影器
     */
    public QueryWebQaQuestionService(
            ProjectQueryUseCase projects,
            AgentRunQueryUseCase runs,
            WebQaQuestionRepository questions,
            WebQaMessageRepository messages,
            WebQaAssistantMessageMaterializer materializer
    ) {
        this.projects = projects;
        this.runs = runs;
        this.questions = questions;
        this.messages = messages;
        this.materializer = materializer;
    }

    @Override
    public WebQaQuestionPage history(QueryWebQaHistoryCommand command) {
        requireIdentity(command.operatorId(), command.projectIdentifier());
        if (command.limit() < 1 || command.limit() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("web QA page size out of range");
        }
        ProjectDetailView project = enabledProject(command.projectIdentifier());
        WebQaCursor after = command.cursor() == null || command.cursor().isBlank()
                ? null : WebQaCursorCodec.decode(command.cursor());
        List<WebQaQuestionRecord> records = questions.findHistory(
                command.operatorId(), project.id(), after, command.limit() + 1);
        boolean hasMore = records.size() > command.limit();
        List<WebQaQuestionRecord> visible = hasMore
                ? records.subList(0, command.limit()) : records;
        List<WebQaQuestionSnapshot> snapshots = new ArrayList<>(visible.size());
        for (WebQaQuestionRecord question : visible) {
            AgentRunSnapshot run = runs.get(question.runId(), command.operatorId());
            snapshots.add(snapshot(question, run));
        }
        String nextCursor = hasMore && !visible.isEmpty()
                ? WebQaCursorCodec.encode(new WebQaCursor(
                        visible.getLast().createdAt(), visible.getLast().id()))
                : null;
        log.info("web_qa history queried traceId={} project={} resultCount={} hasMore={}",
                traceId(), project.identifier(), snapshots.size(), hasMore);
        return new WebQaQuestionPage(snapshots, nextCursor);
    }

    @Override
    public WebQaQuestionSnapshot detail(QueryWebQaDetailCommand command) {
        requireIdentity(command.operatorId(), command.projectIdentifier());
        if (command.questionId() == null) {
            throw new WebQaQuestionNotFoundException();
        }
        ProjectDetailView project = enabledProject(command.projectIdentifier());
        WebQaQuestionRecord question = questions.findVisibleById(
                        command.operatorId(), project.id(), command.questionId())
                .orElseThrow(WebQaQuestionNotFoundException::new);
        AgentRunSnapshot run = runs.get(question.runId(), command.operatorId());
        WebQaQuestionSnapshot result = snapshot(question, run);
        log.info("web_qa detail queried traceId={} questionId={} runId={} project={} branch={} status={}",
                traceId(), question.id(), run.runId(), question.projectIdentifier(), question.branch(), run.status());
        return result;
    }

    private WebQaQuestionSnapshot snapshot(WebQaQuestionRecord question, AgentRunSnapshot run) {
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

    private ProjectDetailView enabledProject(String identifier) {
        try {
            return projects.getEnabledProject(identifier, null);
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
