package io.github.loredock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * LoreDock 单体后端应用入口。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LoreDockApplication {

    /**
     * 启动 LoreDock 后端服务。
     *
     * @param args 进程启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(LoreDockApplication.class, args);
    }
}
