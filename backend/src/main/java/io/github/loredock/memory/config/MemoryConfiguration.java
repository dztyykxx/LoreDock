package io.github.loredock.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.memory.api.MemoryService;
import io.github.loredock.memory.mapper.UserMemoryMapper;
import io.github.loredock.memory.service.MemoryServiceImpl;
import io.github.loredock.memory.service.MemoryWriteJudger;
import io.github.loredock.project.api.ProjectService;
import java.time.Clock;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 记忆模块纯领域服务的 Spring 组装边界；Mapper 由 MyBatis-Plus 按 {@code @Mapper} 扫描注册。 */
@Configuration(proxyBeanMethods = false)
public class MemoryConfiguration {

    /**
     * @param model 平台统一 ChatModel 提供者（与 {@code KnowledgeCurationRunExecutor} 同模式：延迟解析，
     *              未显式启用模型时应用照常启动，判断调用时以明确错误失败，可整体重试）
     * @param objectMapper 结构化 JSON 解析
     * @return 记忆提炼判断器（值得写/重复/冲突仍写）
     */
    @Bean
    public MemoryWriteJudger memoryWriteJudger(ObjectProvider<ChatModel> model, ObjectMapper objectMapper) {
        return new MemoryWriteJudger(model, objectMapper);
    }

    /**
     * @param mapper 记忆事实持久化
     * @param projects 项目稳定范围解析（记忆 → Project(api) 依赖方向）
     * @param judger 提炼判断器
     * @param properties 行为边界配置
     * @param clock 平台统一时钟（审计与频次刷新）
     * @return 记忆稳定契约实现
     */
    @Bean
    public MemoryService memoryService(
            UserMemoryMapper mapper,
            ProjectService projects,
            MemoryWriteJudger judger,
            MemoryProperties properties,
            Clock clock
    ) {
        return new MemoryServiceImpl(mapper, projects, judger, properties, clock);
    }
}
