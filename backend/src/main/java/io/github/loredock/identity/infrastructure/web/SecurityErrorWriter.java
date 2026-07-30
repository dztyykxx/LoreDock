package io.github.loredock.identity.infrastructure.web;

import io.github.loredock.platform.time.TimeProvider;
import io.github.loredock.platform.web.ApiError;
import io.github.loredock.platform.web.ErrorCode;
import io.github.loredock.platform.web.SecurityErrorFactory;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 在请求尚未进入 Spring MVC 时写出与控制器异常一致的安全错误体。
 */
public final class SecurityErrorWriter {

    private final ObjectMapper jsonMapper;
    private final SecurityErrorFactory errorFactory;

    /**
     * @param jsonMapper JSON 序列化器
     * @param timeProvider UTC 时间端口
     */
    public SecurityErrorWriter(ObjectMapper jsonMapper, TimeProvider timeProvider) {
        this.jsonMapper = jsonMapper;
        this.errorFactory = new SecurityErrorFactory(timeProvider);
    }

    /**
     * 写出稳定错误码，不回显请求头、Cookie 或任何凭据来值。
     *
     * @param response Servlet 响应
     * @param code 安全错误码
     * @throws IOException 响应写出失败
     */
    public void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiError error = errorFactory.create(code, List.of());
        jsonMapper.writeValue(response.getOutputStream(), error);
    }
}
