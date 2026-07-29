package io.github.loredock.identity.infrastructure.web;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import io.github.loredock.identity.domain.WebRole;
import org.springframework.context.annotation.Configuration;
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
        registry.addInterceptor(new SaInterceptor(handler -> StpUtil.checkLogin()).isAnnotation(false))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/logout", "/api/v1/system/status")
                .order(0);
        registry.addInterceptor(new SaInterceptor(handler -> StpUtil.checkRole(ADMIN_ROLE)).isAnnotation(false))
                .addPathPatterns("/api/admin/**")
                .order(1);
    }

}
