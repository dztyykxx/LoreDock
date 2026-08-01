package io.github.loredock.code.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.loredock.code.service.AdminCodeSnapshotQueryService;
import io.github.loredock.code.service.CodeQueryServiceImpl;
import io.github.loredock.code.service.CodeSnapshotUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CodeFeatureDisabledConfigurationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(CodeSnapshotUploadService.class, () -> mock(CodeSnapshotUploadService.class))
            .withBean(AdminCodeSnapshotQueryService.class, () -> mock(AdminCodeSnapshotQueryService.class))
            .withBean(CodeQueryServiceImpl.class, () -> mock(CodeQueryServiceImpl.class))
            .withUserConfiguration(
                    AdminCodeSnapshotController.class,
                    CodeSnapshotController.class,
                    CodeSearchController.class,
                    CodeSnippetController.class);

    /**
     * 业务目的：当前 MVP 默认不注册代码上传、快照状态、搜索和片段 HTTP 入口，防止隐藏页面被绕过调用。
     */
    @Test
    void codeHttpEndpointsAreDisabledByDefault() {
        context.run(application -> {
            assertThat(application).doesNotHaveBean(AdminCodeSnapshotController.class);
            assertThat(application).doesNotHaveBean(CodeSnapshotController.class);
            assertThat(application).doesNotHaveBean(CodeSearchController.class);
            assertThat(application).doesNotHaveBean(CodeSnippetController.class);
            System.out.println("测试证据：场景=代码HTTP能力默认关闭，已注册控制器数=0");
        });
    }

    /**
     * 业务目的：保留的代码模块只能在明确启用时恢复入口，确保既有能力没有被本轮物理删除。
     */
    @Test
    void codeHttpEndpointsCanBeExplicitlyEnabled() {
        context.withPropertyValues("loredock.code.enabled=true").run(application -> {
            assertThat(application).hasSingleBean(AdminCodeSnapshotController.class);
            assertThat(application).hasSingleBean(CodeSnapshotController.class);
            assertThat(application).hasSingleBean(CodeSearchController.class);
            assertThat(application).hasSingleBean(CodeSnippetController.class);
            System.out.println("测试证据：场景=代码HTTP能力显式启用，已注册控制器数=4");
        });
    }
}
