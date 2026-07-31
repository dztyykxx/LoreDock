package io.github.loredock.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 验证快速迭代数据库只使用 Flyway 管理的自增 Long 主外键。 */
@Testcontainers
class DatabaseIdentityContractIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_identity_contract")
            .withUsername("loredock")
            .withPassword("loredock_test");

    /**
     * 业务目的：每张业务表都必须由数据库生成 BIGINT 主键，且外键列类型必须一致，
     * 防止 Long 和复合主键继续扩散到 Service、DTO 与前端。
     */
    @Test
    void everyBusinessTableUsesIdentityBigintPrimaryKeyAndBigintForeignKeys() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        List<String> invalidPrimaryKeys = new ArrayList<>();
        List<String> invalidForeignKeys = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         select c.relname as table_name,
                                count(a.attname) as key_columns,
                                string_agg(a.attname || ':' || format_type(a.atttypid, a.atttypmod)
                                    || ':identity=' || a.attidentity::text, ',' order by a.attnum) as definition
                         from pg_class c
                         join pg_namespace n on n.oid=c.relnamespace
                         join pg_constraint pk on pk.conrelid=c.oid and pk.contype='p'
                         join unnest(pk.conkey) with ordinality k(attnum, ordinal) on true
                         join pg_attribute a on a.attrelid=c.oid and a.attnum=k.attnum
                         where n.nspname='public' and c.relkind='r' and c.relname <> 'flyway_schema_history'
                         group by c.relname
                         order by c.relname
                         """)) {
                while (rows.next()) {
                    String definition = rows.getString("definition");
                    if (rows.getInt("key_columns") != 1
                            || !definition.contains("bigint")
                            || !definition.contains("identity=d")) {
                        invalidPrimaryKeys.add(rows.getString("table_name") + " -> " + definition);
                    }
                }
            }
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         select child.relname as table_name, child_column.attname as column_name,
                                format_type(child_column.atttypid, child_column.atttypmod) as child_type,
                                parent.relname as parent_table,
                                format_type(parent_column.atttypid, parent_column.atttypmod) as parent_type
                         from pg_constraint fk
                         join pg_class child on child.oid=fk.conrelid
                         join pg_class parent on parent.oid=fk.confrelid
                         join unnest(fk.conkey, fk.confkey) pair(child_attnum, parent_attnum) on true
                         join pg_attribute child_column
                           on child_column.attrelid=child.oid and child_column.attnum=pair.child_attnum
                         join pg_attribute parent_column
                           on parent_column.attrelid=parent.oid and parent_column.attnum=pair.parent_attnum
                         join pg_namespace n on n.oid=child.relnamespace
                         where fk.contype='f' and n.nspname='public'
                           and child.relname <> 'graphcheckpoint'
                         order by child.relname, child_column.attnum
                         """)) {
                while (rows.next()) {
                    String childType = rows.getString("child_type");
                    String parentType = rows.getString("parent_type");
                    if (!"bigint".equals(childType) || !"bigint".equals(parentType)) {
                        invalidForeignKeys.add(rows.getString("table_name") + "."
                                + rows.getString("column_name") + "(" + childType + ") -> "
                                + rows.getString("parent_table") + "(" + parentType + ")");
                    }
                }
            }
        }

        System.out.printf("测试证据：场景=数据库Long标识基线，非identity主键=%s，非BIGINT外键=%s%n",
                invalidPrimaryKeys, invalidForeignKeys);
        assertThat(invalidPrimaryKeys).as("所有业务表必须使用单列 identity BIGINT 主键").isEmpty();
        assertThat(invalidForeignKeys).as("所有业务外键必须使用 BIGINT").isEmpty();
    }

    /**
     * 业务目的：持久化实体必须与数据库 identity 主键保持一致，防止表已改为 BIGINT 后 Java 层仍以 Long
     * 或手工输入主键写入，造成运行时映射错误。
     */
    @Test
    void everyPersistenceEntityUsesLongAutoIdentity() throws Exception {
        Path classesRoot = Path.of("target/classes");
        List<String> invalidEntities = new ArrayList<>();
        try (var classes = Files.walk(classesRoot.resolve("io/github/loredock"))) {
            for (Path classFile : classes
                    .filter(path -> path.toString().contains("/model/entity/"))
                    .filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().contains("$"))
                    .toList()) {
                String className = classesRoot.relativize(classFile).toString()
                        .replace('/', '.')
                        .replace('\\', '.')
                        .replaceFirst("\\.class$", "");
                Class<?> entityType = Class.forName(className);
                if (entityType.getAnnotation(TableName.class) == null) {
                    continue;
                }
                List<Field> idFields = Arrays.stream(entityType.getDeclaredFields())
                        .filter(field -> field.getAnnotation(TableId.class) != null)
                        .toList();
                if (idFields.size() != 1) {
                    invalidEntities.add(className + " -> TableId数量=" + idFields.size());
                    continue;
                }
                Field idField = idFields.getFirst();
                TableId tableId = idField.getAnnotation(TableId.class);
                if (idField.getType() != Long.class || tableId.type() != IdType.AUTO) {
                    invalidEntities.add(className + " -> " + idField.getName() + ":"
                            + idField.getType().getSimpleName() + ":" + tableId.type());
                }
            }
        }

        System.out.printf("测试证据：场景=Entity Long identity映射，违规实体=%s%n", invalidEntities);
        assertThat(invalidEntities).as("每个持久化实体必须使用 Long 与 IdType.AUTO 主键").isEmpty();
    }
}
