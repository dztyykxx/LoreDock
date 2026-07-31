package io.github.loredock.identity.infrastructure.web;

import cn.dev33.satoken.stp.StpUtil;
import io.github.loredock.identity.domain.WebRole;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 身份路由与角色配置。认证和授权都在服务端入口执行，前端按钮可见性不能替代该边界。
 */
@Configuration(proxyBeanMethods = false)
public class IdentityWebConfiguration implements WebMvcConfigurer {

    private static final String ADMIN_ROLE = WebRole.ADMIN.name();

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 先判定会话再判定角色，使未登录管理请求稳定返回 401，而已登录成员返回 403。
        registry.addInterceptor(requestOnly(() -> StpUtil.checkLogin()))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/logout", "/api/v1/system/status")
                .order(0);
        registry.addInterceptor(requestOnly(() -> StpUtil.checkRole(ADMIN_ROLE)))
                .addPathPatterns("/api/admin/**")
                .order(1);
    }

    private HandlerInterceptor requestOnly(Runnable authorization) {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    Object handler
            ) {
                // SSE 完成后的 ASYNC 重派发不再具有 Sa-Token ThreadLocal；认证已在首次 REQUEST 派发完成。
                if (request.getDispatcherType() == DispatcherType.REQUEST) {
                    authorization.run();
                }
                return true;
            }
        };
    }

}
