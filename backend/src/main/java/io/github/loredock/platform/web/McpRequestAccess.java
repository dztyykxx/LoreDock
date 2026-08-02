package io.github.loredock.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** MCP HTTP 请求内的读写授权事实，只在当前同步请求线程中传递，不保存 Token。 */
public final class McpRequestAccess {

    private static final String ACCESS_ATTRIBUTE = McpRequestAccess.class.getName() + ".write";

    private McpRequestAccess() {
    }

    /** 标记已经由认证拦截器验证的请求是否具有草稿写入权限。 */
    public static void mark(HttpServletRequest request, boolean writeAllowed) {
        request.setAttribute(ACCESS_ATTRIBUTE, writeAllowed);
    }

    /** 写工具调用时再次检查权限。 */
    public static void requireWrite() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                || !Boolean.TRUE.equals(attributes.getRequest().getAttribute(ACCESS_ATTRIBUTE))) {
            throw new IllegalStateException("MCP_WRITE_TOKEN_REQUIRED");
        }
    }
}
