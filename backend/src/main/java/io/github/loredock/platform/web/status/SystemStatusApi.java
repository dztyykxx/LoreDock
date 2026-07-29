package io.github.loredock.platform.web.status;

/**
 * 对外提供不含敏感配置的系统运行状态。
 */
public interface SystemStatusApi {

    /**
     * 查询当前服务的公开运行状态。
     *
     * @return 仅包含服务名和状态的响应
     */
    SystemStatusResponse status();
}
