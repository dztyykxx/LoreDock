package io.github.loredock.agent.infrastructure.model;

import io.github.loredock.agent.infrastructure.config.AgentProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.net.URI;
import java.io.IOException;
import java.time.Duration;

/** 根据受控配置创建 DeepSeek OpenAI 兼容模型，不记录密钥、端点或请求/响应正文。 */
public final class DeepSeekChatModelFactory {

    private DeepSeekChatModelFactory() {
    }

    /**
     * @param properties 已校验的模型名称、端点、进程 secret、超时和有限重试
     * @return 同时支持普通与流式工具调用的 Spring AI ChatModel
     */
    public static ChatModel create(AgentProperties.Model properties) {
        if (!properties.configured()) {
            throw new IllegalStateException("Agent model is not configured");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        JdkClientHttpConnector connector = new JdkClientHttpConnector(httpClient);
        connector.setReadTimeout(properties.readTimeout());
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .webClientBuilder(WebClient.builder().clientConnector(connector))
                .responseErrorHandler(new DeepSeekResponseErrorHandler())
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(properties.name())
                .temperature(0.0)
                .parallelToolCalls(false)
                .internalToolExecutionEnabled(false)
                .streamUsage(true)
                .store(false)
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build())
                .build();
        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(properties.maxRetries() + 1)
                .fixedBackoff(Duration.ofMillis(100))
                .retryOn(DeepSeekChatModelFactory::retryable)
                .traversingCauses()
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(retry)
                .build();
    }

    private static boolean retryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof NonTransientAiException) {
                return false;
            }
            if (current instanceof TransientAiException
                    || current instanceof ResourceAccessException
                    || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 只按状态类别生成脱敏异常，禁止把上游响应正文或请求信息放入异常链。 */
    private static final class DeepSeekResponseErrorHandler implements ResponseErrorHandler {

        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return response.getStatusCode().isError();
        }

        @Override
        public void handleError(URI url, HttpMethod method, ClientHttpResponse response) throws IOException {
            HttpStatusCode status = response.getStatusCode();
            String safeMessage = "model upstream status=" + status.value();
            if (status.value() == 429 || status.is5xxServerError()) {
                throw new TransientAiException(safeMessage);
            }
            throw new NonTransientAiException(safeMessage);
        }
    }
}
