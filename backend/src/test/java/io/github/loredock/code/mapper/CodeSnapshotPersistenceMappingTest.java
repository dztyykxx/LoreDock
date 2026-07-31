package io.github.loredock.code.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.loredock.code.model.entity.CodeIndexGenerationEntity;
import io.github.loredock.code.model.entity.CodeSnapshotEntity;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CodeSnapshotPersistenceMappingTest {

    /**
     * 业务目的：快照和 generation 实体必须逐字段绑定 V4 列名，防止全局关闭驼峰映射后出现静默空值或错列。
     */
    @Test
    void codeEntitiesDeclareExplicitTableAndColumnMappings() {
        assertExplicitMapping(CodeSnapshotEntity.class, "code_snapshot");
        assertExplicitMapping(CodeIndexGenerationEntity.class, "code_index_generation");
    }

    private void assertExplicitMapping(Class<?> entityType, String tableName) {
        assertThat(entityType).hasAnnotation(TableName.class);
        assertThat(entityType.getAnnotation(TableName.class).value()).isEqualTo(tableName);
        Field[] persistentFields = Arrays.stream(entityType.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toArray(Field[]::new);
        assertThat(Arrays.stream(persistentFields).filter(field -> field.isAnnotationPresent(TableId.class)))
                .hasSize(1);
        assertThat(Arrays.stream(persistentFields)
                .filter(field -> !field.isAnnotationPresent(TableId.class))
                .allMatch(field -> field.isAnnotationPresent(TableField.class)))
                .isTrue();
    }
}
