package io.github.loredock.memory.converter;

/**
 * 记忆 HTTP 契约。GET 幂等、登录即可读；写操作仅管理员（由统一 `/api/admin/**`
 * 服务端拦截链授权，Controller 不信任客户端角色字段）。字段非法 400，项目无效/范围
 * 越界 400，不存在 404，判断模型不可用 503；客户端不能提交内部来源、频次或审计字段。
 */
public final class MemoryHttpContract {

    public static final String LIST_PATH = "/api/memories";
    public static final String ADMIN_PATH = "/api/admin/memories";
    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    private MemoryHttpContract() {
    }
}
