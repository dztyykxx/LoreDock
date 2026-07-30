package io.github.loredock.poc;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.function.FunctionToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentDependencySafetyTest {

    /**
     * 业务目的：证明生产候选类路径彻底移除了受 CVE-2026-16723 影响的 fastjson 1.x，
     * 防止仅在 POM 中声明排除但仍被其他传递依赖重新带入。
     */
    @Test
    void shouldNotLoadVulnerableFastjsonOneFromRuntimeClasspath() {
        assertThatThrownBy(() -> Class.forName("com.alibaba.fastjson.JSON"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    /**
     * 业务目的：在排除 fastjson 与 A2A SDK 后链接 T6A 实际使用的核心类，
     * 防止安全裁剪破坏 ReactAgent、限制 Hook 或工具回调。
     */
    @Test
    void shouldLinkOnlyRequiredAgentRuntimeClassesAfterExclusions() {
        assertThat(ReactAgent.class).isNotNull();
        assertThat(ModelCallLimitHook.class).isNotNull();
        assertThat(ToolCallLimitHook.class).isNotNull();
        assertThat(FunctionToolCallback.class).isNotNull();
        System.out.println("POC场景=安全排除 ReactAgent/Hook/ToolCallback 核心类链接成功");
    }
}
