package io.github.loredock.platform.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.loredock.agent.api.KnowledgeTaskRequestException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor();
        GlobalExceptionHandler handler = new GlobalExceptionHandler(Clock.systemUTC(), redactor);
        mockMvc = MockMvcBuilders.standaloneSetup(new ErrorProbeController())
                .setControllerAdvice(handler)
                .addFilters(new TraceIdFilter())
                .build();
    }

    /**
     * 业务目的：已分类业务失败必须保持稳定 HTTP 语义和 trace ID，防止前端依赖易变异常文本判断错误。
     */
    @Test
    void knownBusinessErrorReturnsStableBodyAndTraceId() throws Exception {
        mockMvc.perform(get("/test/errors/known").header(TraceIdFilter.TRACE_HEADER, "trace-safe-123"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(TraceIdFilter.TRACE_HEADER, "trace-safe-123"))
                .andExpect(jsonPath("$.code").value("OBJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("对象不存在"))
                .andExpect(jsonPath("$.traceId").value("trace-safe-123"))
                .andExpect(jsonPath("$.timestamp", matchesPattern(".*Z")));
    }

    /**
     * 业务目的：未知异常不得把数据库地址、绝对路径或密钥带回客户端，防止内部实现和凭据泄露。
     */
    @Test
    void unexpectedErrorReturnsOnlyGenericSafeMessage() throws Exception {
        mockMvc.perform(get("/test/errors/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务暂时不可用"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-value"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jdbc:postgresql"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/Users/demo"))));
    }

    /**
     * 业务目的：内置知识整理 Skill 缺失属于服务能力不可用，而不是用户操作造成的任务状态冲突；
     * 防止页面误导管理员反复调整正确的草稿勾选项。
     */
    @Test
    void missingKnowledgeAgentDefinitionReturnsServiceUnavailable() throws Exception {
        mockMvc.perform(get("/test/errors/knowledge-definition"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_TASK_DEFINITION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("知识整理能力暂时不可用"));
    }

    /**
     * 业务目的：一次返回全部安全字段错误可减少反复提交，同时禁止回显敏感字段原值。
     */
    @Test
    void multipleValidationErrorsReturnPathsAndReasonsWithoutValues() throws Exception {
        mockMvc.perform(post("/test/errors/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"token\":\"abc123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(2)))
                .andExpect(jsonPath("$.fieldErrors[*].field", org.hamcrest.Matchers.containsInAnyOrder("title", "token")))
                .andExpect(jsonPath("$.fieldErrors[*].reason", org.hamcrest.Matchers.containsInAnyOrder("NOT_BLANK", "SIZE")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("abc123"))));
    }

    @RestController
    @RequestMapping("/test/errors")
    public static class ErrorProbeController {

        @GetMapping("/known")
        void known() {
            throw new ApplicationException(ErrorCode.OBJECT_NOT_FOUND, "token=secret-value");
        }

        @GetMapping("/unexpected")
        void unexpected() {
            throw new IllegalStateException(
                    "password=secret-value jdbc:postgresql://internal/db /Users/demo/private/file");
        }

        @GetMapping("/knowledge-definition")
        void missingKnowledgeDefinition() {
            throw new KnowledgeTaskRequestException(KnowledgeTaskRequestException.Code.AGENT_DEFINITION_INVALID);
        }

        @PostMapping("/validation")
        void validation(@Valid @RequestBody ProbeRequest request) {
            // 方法存在只为验证 Controller 入口的校验边界；失败请求不得执行到这里。
        }
    }

    record ProbeRequest(@NotBlank String title, @Size(min = 8) String token) {
    }
}
