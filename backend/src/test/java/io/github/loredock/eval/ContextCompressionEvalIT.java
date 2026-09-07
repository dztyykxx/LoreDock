package io.github.loredock.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

/**
 * 显式启用的真实模型评估入口，不启动 Web、数据库或完整 Agent 图。
 * 使用已有 Spring AI OpenAI 兼容模型客户端；业务和裁判独立配置，温度统一为零。
 */
@EnabledIfSystemProperty(named = "loredock.context-eval", matches = "true")
class ContextCompressionEvalIT {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 业务目的：用固定长会话实际测量压缩续做，语义失败保留在报告中，执行或裁判错误使测试失败。 */
    @Test
    void evaluatesOrRejudgesFixedConversations() throws Exception {
        String mode = System.getProperty("loredock.context-eval.mode", "all");
        if (!List.of("all", "run", "judge", "calibrate").contains(mode)) {
            throw new IllegalArgumentException("mode 必须为 all/run/judge/calibrate");
        }
        Path directory = Path.of(System.getProperty("loredock.context-eval.dir",
                "target/context-compression-eval/" + Instant.now().toString().replace(':', '-'))).toAbsolutePath();
        Files.createDirectories(directory);
        var runner = new ContextCompressionEvalRunner(mapper);
        String businessName = setting("LOREDOCK_AGENT_MODEL_NAME", "deepseek-v4-flash");
        String judgeName = System.getProperty("loredock.context-eval.judge-model", setting("LOREDOCK_EVAL_JUDGE_MODEL", businessName));
        if (mode.equals("calibrate")) {
            calibrate(model(judgeName, true), judgeName, directory);
            return;
        }
        ContextCompressionEvalRunner.Report raw;
        if (mode.equals("judge")) {
            String input = System.getProperty("loredock.context-eval.input");
            if (input == null) throw new IllegalArgumentException("离线重判需设置 loredock.context-eval.input");
            if (Path.of(input).toAbsolutePath().normalize().equals(directory.resolve("judged.json").normalize())) {
                throw new IllegalArgumentException("重判不能覆盖输入报告，请选择新目录");
            }
            raw = runner.read(Path.of(input));
        } else {
            Path output = directory.resolve("raw.json");
            if (Files.exists(output)) throw new IllegalArgumentException("已有原始报告，请使用新目录，避免覆盖实测证据");
            var data = ContextCompressionEvalFixture.load(mapper);
            String selected = System.getProperty("loredock.context-eval.case", "ALL");
            var cases = data.cases().stream().filter(item -> "ALL".equals(selected) || item.id().equals(selected)).toList();
            if (cases.isEmpty()) throw new IllegalArgumentException("未知样例编号");
            data = new ContextCompressionEvalFixture.Dataset(data.version(), data.budget(), List.of(), cases);
            int repetitions = Integer.getInteger("loredock.context-eval.repetitions", 1);
            if (repetitions < 1 || repetitions > 3) throw new IllegalArgumentException("本轻量评估支持 1 至 3 次整组实验");
            ChatModel business = model(businessName, false);
            List<ContextCompressionEvalRunner.CaseResult> results = new ArrayList<>();
            Files.writeString(directory.resolve("run-plan.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    Map.of("cases", cases.stream().map(ContextCompressionEvalFixture.Case::id).toList(),
                            "repetitions", repetitions, "expectedResults", cases.size() * repetitions)));
            raw = runner.report(data, businessName, results);
            runner.write(raw, output);
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                for (var sample : cases) {
                    System.out.printf("开始真实配对评估：case=%s repetition=%d model=%s%n", sample.id(), repetition, businessName);
                    results.add(runner.run(sample, data.budget(), business, repetition));
                    raw = runner.report(data, businessName, results);
                    runner.write(raw, output);
                }
            }
        }
        ContextCompressionEvalRunner.Report result = raw;
        if (mode.equals("all") || mode.equals("judge")) {
            Path output = directory.resolve("judged.json");
            if (Files.exists(output)) throw new IllegalArgumentException("已有判分报告，请使用新目录保留旧判分");
            result = runner.judge(raw, model(judgeName, true), judgeName, output);
        }
        System.out.printf("测试证据：真实评估完成，记录=%d，输出=%s，模式=%s%n", result.results().size(), directory, mode);
        assertThat(result.results()).isNotEmpty();
        for (var item : result.results()) {
            assertThat(item.full().errors()).as(item.sample().id() + " 完整组执行").isEmpty();
            assertThat(item.compressed().errors()).as(item.sample().id() + " 压缩组执行").isEmpty();
            if (!mode.equals("run")) {
                assertThat(item.full().judgement().status()).as(item.sample().id() + " 完整组Judge").isEqualTo("JUDGED");
                assertThat(item.compressed().judgement().status()).as(item.sample().id() + " 压缩组Judge").isEqualTo("JUDGED");
            }
        }
    }

    /** 两条人工构造的错误答案必须被裁判拒绝；结果先落盘，再检查，错误不能伪装成正确拒绝。 */
    private void calibrate(ChatModel model, String name, Path directory) throws Exception {
        Path output = directory.resolve("calibration.json");
        if (Files.exists(output)) throw new IllegalArgumentException("已有校准报告，请选择新目录");
        var data = ContextCompressionEvalFixture.load(mapper);
        var judge = new ContextCompressionEvalJudge(mapper);
        var samples = List.of(data.cases().get(2), data.cases().get(4));
        var answers = List.of("最终目录是运维手册，我会沿用最早的目录安排，不采用开发指南。",
                "导入任务失败后自动重试3次，这是已确认的规则，不需要再问负责人。");
        List<ContextCompressionEvalJudge.Judgement> judgements = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) judgements.add(judge.judge(samples.get(i), answers.get(i), model));
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of("judgeModel", name,
                "promptVersion", ContextCompressionEvalJudge.PROMPT_VERSION, "instruction", ContextCompressionEvalJudge.INSTRUCTION,
                "knownBadAnswers", answers, "judgements", judgements));
        for (var judgement : judgements) {
            assertThat(judgement.status()).isEqualTo("JUDGED");
            assertThat(judgement.passed()).isFalse();
        }
        System.out.printf("测试证据：Judge校准=%d条已知错误答案均拒绝，模型=%s，输出=%s%n", judgements.size(), name, output);
    }

    /** 只使用环境变量中的凭据，不写入报告；禁用客户端自动重试以固定实际请求数。 */
    private static ChatModel model(String name, boolean judge) {
        String key = setting(judge ? "LOREDOCK_EVAL_JUDGE_API_KEY" : "LOREDOCK_AGENT_MODEL_API_KEY",
                judge ? setting("LOREDOCK_AGENT_MODEL_API_KEY", "") : "");
        if (key.isBlank()) throw new IllegalStateException("请设置 LOREDOCK_AGENT_MODEL_API_KEY 或独立 Judge 密钥环境变量");
        String base = setting(judge ? "LOREDOCK_EVAL_JUDGE_BASE_URL" : "LOREDOCK_AGENT_MODEL_BASE_URL",
                setting("LOREDOCK_AGENT_MODEL_BASE_URL", "https://api.deepseek.com"));
        var transport = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
        transport.setReadTimeout(Duration.ofSeconds(120));
        var api = OpenAiApi.builder().apiKey(key).baseUrl(base)
                .restClientBuilder(RestClient.builder().requestFactory(transport)).build();
        return OpenAiChatModel.builder().openAiApi(api).retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(OpenAiChatOptions.builder().model(name).temperature(0.0).maxTokens(ContextCompressionEvalRunner.OUTPUT_LIMIT)
                        .internalToolExecutionEnabled(false).parallelToolCalls(false).build()).build();
    }

    private static String setting(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
