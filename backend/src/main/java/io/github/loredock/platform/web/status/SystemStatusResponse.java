package io.github.loredock.platform.web.status;

/**
 * 系统公开运行状态响应，不承载连接串、路径或环境变量等配置。
 *
 * @param service 服务标识
 * @param status 运行状态
 */
public record SystemStatusResponse(String service, String status) {
}
