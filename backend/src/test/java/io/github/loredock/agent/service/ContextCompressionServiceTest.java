package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 受限压缩调用单元测试：ID 子集校验与结构化解析（任务 4.1）。
 */
class ContextCompressionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContextTokenEstimator estimator = new ContextTokenEstimator();

    /** 业务目的：压缩返回的保留 ID 必须来自输入 ID 全集，非法 ID（输入中不存在）直接拒绝结果。 */
    @Test
    void rejectsCompressionOutputWithUnknownIds() {
        ContextCompressionService service = new ContextCompressionService(objectMapper,
                org.mockito.Mockito.mock(KnowledgeTaskMessageMapper.class), estimator);
        ChatModel model = scripted("{\"summary\":\"已完成前两轮\",\"retainedReferenceIds\":[\"EVIDENCE:999\"],"
                + "\"retainedDecisionIds\":[],\"retainedQuestionIds\":[]}");
        ContextCompressionService.IdUniverse ids = new ContextCompressionService.IdUniverse(
                List.of("EVIDENCE:88"), List.of("decision-1"), List.of());

        assertThatThrownBy(() -> service.summarize(model,
                List.of(new ContextCompressionService.OldTurn("用户1", "结论1")), 600, ids))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EVIDENCE:999");
        System.out.printf("测试证据：场景=压缩非法ID拒绝，异常信息含非法ID=%s%n", true);
    }

    /** 业务目的：合法压缩结果返回摘要与 ID 子集，供滚动摘要复用。 */
    @Test
    void acceptsSubsetIdsAndReturnsSummary() {
        ContextCompressionService service = new ContextCompressionService(objectMapper,
                org.mockito.Mockito.mock(KnowledgeTaskMessageMapper.class), estimator);
        ChatModel model = scripted("{\"summary\":\"前两轮已完成，剩余待审。\",\"retainedReferenceIds\":[],"
                + "\"retainedDecisionIds\":[\"decision-1\"],\"retainedQuestionIds\":[\"q0\"]}");

        ContextCompressionService.CompressionResult result = service.summarize(model,
                List.of(new ContextCompressionService.OldTurn("用户1", "结论1")), 600,
                new ContextCompressionService.IdUniverse(List.of(), List.of("decision-1"), List.of("q0")));

        assertThat(result.summary()).contains("前两轮已完成");
        assertThat(result.retainedDecisionIds()).containsExactly("decision-1");
        System.out.printf("测试证据：场景=压缩子集校验通过，摘要=%s%n", result.summary());
    }

    private static ChatModel scripted(String json) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(json))),
                        ChatResponseMetadata.builder().build());
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
    }
}
