package io.github.loredock.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * 为不启动完整 Spring 上下文的真实数据库集成测试创建 MyBatis-Plus Mapper 代理。
 */
public final class MybatisMapperFactory {

    private MybatisMapperFactory() {
    }

    /**
     * @param dataSource 隔离测试数据库
     * @param mapperType Mapper 接口类型
     * @return 具有 MyBatis-Plus 基础语句注入能力的 Mapper 代理
     */
    public static <T> T create(DataSource dataSource, Class<T> mapperType) {
        try {
            MybatisConfiguration configuration = new MybatisConfiguration();
            // 与生产 application.yml 保持一致：实体字段必须依赖显式 @TableField 映射，
            // 自定义 SQL 未声明结果映射时应在测试中尽早暴露，而不是被默认驼峰规则掩盖。
            configuration.setMapUnderscoreToCamelCase(false);
            configuration.addMapper(mapperType);
            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setConfiguration(configuration);
            SqlSessionFactory sessionFactory = factoryBean.getObject();
            if (sessionFactory == null) {
                throw new IllegalStateException("MyBatis-Plus 测试会话工厂创建失败");
            }
            return new SqlSessionTemplate(sessionFactory).getMapper(mapperType);
        } catch (Exception exception) {
            throw new IllegalStateException("MyBatis-Plus 测试 Mapper 创建失败", exception);
        }
    }
}
