package io.github.loredock.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * MCP HTTP 请求内的读写授权与项目范围事实，只在当前同步请求线程中传递，不保存 Token。
 *
 * <p>项目字段来自客户端部署配置的 {@code X-LoreDock-Project} 请求头，用于把检索范围
 * 锁定为部署者声明的项目，而不是依赖模型每次猜测工具参数。</p>
 */
public final class McpRequestAccess {

    private static final String ACCESS_ATTRIBUTE = McpRequestAccess.class.getName() + ".write";
    private static final String PROJECT_ATTRIBUTE = McpRequestAccess.class.getName() + ".project";

    private McpRequestAccess() {
    }

    /**
     * 标记已经由认证拦截器验证的请求是否具有草稿写入权限，以及客户端配置锁定的项目。
     *
     * @param writeAllowed 是否携带有效的写 Token
     * @param project 客户端配置的项目标识；未配置时为 null
     */
    public static void mark(HttpServletRequest request, boolean writeAllowed, String project) {
        request.setAttribute(ACCESS_ATTRIBUTE, writeAllowed);
        request.setAttribute(PROJECT_ATTRIBUTE, project);
    }

    /** 写工具调用时再次检查权限。 */
    public static void requireWrite() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                || !Boolean.TRUE.equals(attributes.getRequest().getAttribute(ACCESS_ATTRIBUTE))) {
            throw new IllegalStateException("MCP_WRITE_TOKEN_REQUIRED");
        }
    }

    /**
     * @return 客户端部署配置锁定的项目标识；未配置或为空时返回 null
     */
    public static String configuredProject() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        Object value = attributes.getRequest().getAttribute(PROJECT_ATTRIBUTE);
        if (!(value instanceof String project) || project.isBlank()) {
            return null;
        }
        return project;
    }
}
