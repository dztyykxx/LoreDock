package io.github.loredock.auth.config;

import cn.dev33.satoken.stp.StpInterface;
import io.github.loredock.auth.service.AccountService;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 将服务端固定账号目录适配为 Sa-Token 角色来源；与 MVC 路由配置分离，避免无身份依赖的 Web 切片被迫加载账号目录。
 */
@Configuration(proxyBeanMethods = false)
public class IdentityRoleConfiguration {

    /**
     * loginId 是唯一角色查询键，客户端请求字段与 Cookie 内容都不能自行声明角色。
     *
     * @param directory 服务端固定账号目录
     * @return Sa-Token 角色解析器
     */
    @Bean
    public StpInterface stpInterface(AccountService directory) {
        return new StpInterface() {
            @Override
            public List<String> getPermissionList(Object loginId, String loginType) {
                return List.of();
            }

            @Override
            public List<String> getRoleList(Object loginId, String loginType) {
                return directory.findByUsername(String.valueOf(loginId))
                        .map(account -> List.of(account.role().name()))
                        .orElseGet(List::of);
            }
        };
    }
}
