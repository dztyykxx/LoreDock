package io.github.loredock.memory.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.memory.config.MemoryProperties;
import io.github.loredock.memory.service.MemoryWriteJudger;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** MemoryService 相关集成测试的夹具：稳定项目范围假实现 + 永不调用的判断器 + 默认配置。 */
public final class MemoryTestFixtures {

    private MemoryTestFixtures() {
    }

    /**
     * 支持给定主键集合的 ProjectService 假实现：resolveScope(主键) 直接命中，
     * 其余入口抛不支持异常（本测试不覆盖字符串入口分支）。
     */
    public static ProjectService projectService(Long... projectIds) {
        Map<Long, ProjectScope> scopes = new HashMap<>();
        for (Long projectId : projectIds) {
            scopes.put(projectId, new ProjectScope(projectId, "project-" + projectId,
                    "项目" + projectId, true, null, null));
        }
        return new ProjectService() {
            @Override
            public ProjectScope resolveEnabledScope(String projectIdentifier, String branchName) {
                throw new UnsupportedOperationException("本测试未使用字符串入口");
            }

            @Override
            public ProjectScope resolveScope(String projectIdentifier, String branchName) {
                throw new UnsupportedOperationException("本测试未使用字符串入口");
            }

            @Override
            public ProjectScope resolveScope(Long projectId) {
                ProjectScope scope = scopes.get(projectId);
                if (scope == null) {
                    throw new IllegalArgumentException("项目不存在：" + projectId);
                }
                return scope;
            }

            @Override
            public ProjectScope resolveScope(Long projectId, Long branchId) {
                ProjectScope scope = scopes.get(projectId);
                if (scope == null) {
                    throw new IllegalArgumentException("项目不存在：" + projectId);
                }
                return scope;
            }
        };
    }

    /** 指定项目处于「已停用」状态的 ProjectService 假实现（禁用项目场景测试用）。 */
    public static ProjectService disabledProjectService(Long... projectIds) {
        Map<Long, ProjectScope> scopes = new HashMap<>();
        for (Long projectId : projectIds) {
            scopes.put(projectId, new ProjectScope(projectId, "project-" + projectId,
                    "项目" + projectId, false, null, null));
        }
        return new ProjectService() {
            @Override
            public ProjectScope resolveEnabledScope(String projectIdentifier, String branchName) {
                throw new UnsupportedOperationException("本测试未使用字符串入口");
            }

            @Override
            public ProjectScope resolveScope(String projectIdentifier, String branchName) {
                throw new UnsupportedOperationException("本测试未使用字符串入口");
            }

            @Override
            public ProjectScope resolveScope(Long projectId) {
                ProjectScope scope = scopes.get(projectId);
                if (scope == null) {
                    throw new IllegalArgumentException("项目不存在：" + projectId);
                }
                return scope;
            }

            @Override
            public ProjectScope resolveScope(Long projectId, Long branchId) {
                ProjectScope scope = scopes.get(projectId);
                if (scope == null) {
                    throw new IllegalArgumentException("项目不存在：" + projectId);
                }
                return scope;
            }
        };
    }

    /** 将单个模型包装为 ObjectProvider，匹配 MemoryWriteJudger 延迟解析的生产装配方式。 */
    public static ObjectProvider<ChatModel> single(ChatModel model) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("chatModel", model);
        return factory.getBeanProvider(ChatModel.class);
    }

    /** 测试场景不得触发写入判断：被调用即视为用例失控。 */
    public static MemoryWriteJudger judgerNeverCalled() {
        return new MemoryWriteJudger(single(new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("本测试不应触发记忆写入判断调用");
            }
        }), new ObjectMapper());
    }

    /** 默认行为边界（与生产默认一致：预载 30 / 兜底 3 / 摘要 300 / 正文 4000 / 候选 3 / 预算 10 / 召回 50）。 */
    public static MemoryProperties properties() {
        return new MemoryProperties(30, 3, 300, 200, 4000, 3, 10, 50);
    }

    /** 只覆盖单 run 写入预算的默认配置变体（预算场景测试用）。 */
    public static MemoryProperties properties(int writeBudgetPerRun) {
        return new MemoryProperties(30, 3, 300, 200, 4000, 3, writeBudgetPerRun, 50);
    }
}
