package io.github.loredock.persistence;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.loredock.job.infrastructure.persistence.BackgroundJobEntity;
import io.github.loredock.project.infrastructure.persistence.ProjectBranchEntity;
import io.github.loredock.project.infrastructure.persistence.ProjectSpaceEntity;
import io.github.loredock.storage.infrastructure.persistence.StoredObjectEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceMappingContractTest {

    /**
     * 业务目的：数据库实体必须逐字段绑定 Flyway 列名，防止重命名或全局驼峰配置导致静默读写错误。
     */
    @Test
    void persistenceEntitiesDeclareExplicitTableIdAndFieldMappings() {
        assertExplicitMapping(StoredObjectEntity.class, "stored_object");
        assertExplicitMapping(BackgroundJobEntity.class, "background_job");
        assertExplicitMapping(ProjectSpaceEntity.class, "project_space");
        assertExplicitMapping(ProjectBranchEntity.class, "project_branch");
    }

    /**
     * 业务目的：SQL 只能在 Java 代码或 Mapper 方法注解中维护，防止 XML 与接口分散后产生行为漂移。
     */
    @Test
    void backendResourcesContainNoMybatisXmlMapper() throws IOException {
        Path resources = Path.of("src/main/resources");
        try (var paths = Files.walk(resources)) {
            assertThat(paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml")))
                    .isEmpty();
        }
    }

    private void assertExplicitMapping(Class<?> entityType, String tableName) {
        assertThat(entityType).hasAnnotation(TableName.class);
        assertThat(entityType.getAnnotation(TableName.class).value()).isEqualTo(tableName);

        Field[] persistentFields = Arrays.stream(entityType.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toArray(Field[]::new);
        assertThat(persistentFields).isNotEmpty();
        assertThat(Arrays.stream(persistentFields).filter(field -> field.isAnnotationPresent(TableId.class)))
                .hasSize(1);
        assertThat(Arrays.stream(persistentFields)
                .filter(field -> !field.isAnnotationPresent(TableId.class))
                .allMatch(field -> field.isAnnotationPresent(TableField.class)))
                .isTrue();
    }
}
