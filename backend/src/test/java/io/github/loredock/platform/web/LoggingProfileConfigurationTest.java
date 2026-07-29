package io.github.loredock.platform.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingProfileConfigurationTest {

    private static final String STRUCTURED_CONSOLE_FORMAT = "logging.structured.format.console";

    /**
     * 业务目的：宿主机开发必须获得便于阅读的控制台日志，防止生产日志采集格式再次污染默认开发体验。
     */
    @Test
    void 默认profile不强制输出结构化Json() throws IOException {
        assertThat(propertyValue("application.yml", STRUCTURED_CONSOLE_FORMAT)).isNull();
    }

    /**
     * 业务目的：生产日志必须保持机器可解析格式，防止改善本地输出时意外破坏日志采集契约。
     */
    @Test
    void 生产profile启用Logstash结构化Json() throws IOException {
        assertThat(propertyValue("application-prod.yml", STRUCTURED_CONSOLE_FORMAT)).isEqualTo("logstash");
    }

    private Object propertyValue(String resourcePath, String propertyName) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        assertThat(resource.exists()).as("配置文件 %s 必须存在", resourcePath).isTrue();
        return new YamlPropertySourceLoader().load(resourcePath, resource).stream()
                .map(propertySource -> propertySource.getProperty(propertyName))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }
}
