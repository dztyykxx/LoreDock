package io.github.loredock.storage.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.persistence.MybatisMapperFactory;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;
import io.github.loredock.storage.config.StorageProperties;
import io.github.loredock.storage.mapper.StoredObjectMapper;
import io.github.loredock.storage.model.result.ObjectMetadata;
import io.github.loredock.storage.model.result.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class LocalFileObjectStorageIT {

    private static final Instant FIXED_TIME = Instant.parse("2026-07-29T12:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_storage_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    private static JdbcTemplate jdbcTemplate;
    private static StoredObjectMapper storedObjectMapper;

    @TempDir
    private Path storageRoot;

    @BeforeAll
    static void migrateDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        storedObjectMapper = MybatisMapperFactory.create(dataSource, StoredObjectMapper.class);
    }

    @BeforeEach
    void clearMetadata() {
        jdbcTemplate.update("delete from background_job");
        jdbcTemplate.update("delete from stored_object");
    }

    /**
     * 业务目的：对象写入后必须能按原字节读取并返回可信校验信息，防止上传内容与元数据不一致。
     */
    @Test
    void putAndGetPreserveBytesAndChecksumMetadata() throws Exception {
        LocalFileObjectStorage storage = storage();
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        StoredObject stored = storage.put(
                new ByteArrayInputStream(content),
                new ObjectMetadata("greeting.txt", "text/plain")
        );

        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.sha256()).isEqualTo(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(storage.get(stored.objectKey()).readAllBytes()).isEqualTo(content);
        assertThat(storage.exists(stored.objectKey())).isTrue();
    }

    /**
     * 业务目的：成功发布的对象必须跨应用实例继续可读，防止重启后只剩数据库记录或只剩文件。
     */
    @Test
    void rebuiltAdapterReadsObjectFromSameRootAndDatabase() throws Exception {
        StoredObject stored = storage().put(
                new ByteArrayInputStream("persistent".getBytes(StandardCharsets.UTF_8)),
                new ObjectMetadata("persistent.md", "text/markdown")
        );

        LocalFileObjectStorage restarted = storage();

        assertThat(restarted.get(stored.objectKey()).readAllBytes())
                .isEqualTo("persistent".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 业务目的：不存在对象必须返回稳定错误且删除保持幂等，防止调用方把空内容误认为真实文件。
     */
    @Test
    void missingObjectReadFailsAndRepeatedDeleteSucceeds() {
        LocalFileObjectStorage storage = storage();
        String missingKey = "00000000-0000-0000-0000-000000000051";

        assertThatThrownBy(() -> storage.get(missingKey))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.OBJECT_NOT_FOUND));

        storage.delete(missingKey);
        storage.delete(missingKey);
        assertThat(storage.exists(missingKey)).isFalse();
    }

    /**
     * 业务目的：输入流中途失败时不得留下半成品或元数据，防止后续读取损坏对象。
     */
    @Test
    void interruptedWriteCleansTemporaryFileAndMetadata() throws Exception {
        LocalFileObjectStorage storage = storage();
        InputStream failing = new InputStream() {
            private int readCount;

            @Override
            public int read() throws IOException {
                if (readCount++ < 3) {
                    return 'a';
                }
                throw new IOException("simulated stream failure");
            }
        };

        assertThatThrownBy(() -> storage.put(failing, new ObjectMetadata("broken.txt", "text/plain")))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.STORAGE_WRITE_FAILED));
        try (var paths = Files.walk(storageRoot)) {
            assertThat(paths.filter(Files::isRegularFile).count()).isZero();
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from stored_object", Integer.class)).isZero();
    }

    /**
     * 业务目的：路径穿越、绝对路径和反斜杠逃逸必须在文件访问前被拒绝，防止读取存储根目录之外的文件。
     */
    @Test
    void invalidObjectKeyCannotEscapeStorageRoot() {
        LocalFileObjectStorage storage = storage();

        for (String key : List.of("../secret", "/tmp/secret", "..\\secret")) {
            assertThatThrownBy(() -> storage.get(key))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_OBJECT_KEY));
        }
    }

    /**
     * 业务目的：原始文件名只能作为元数据，防止恶意文件名改变实际落盘位置。
     */
    @Test
    void maliciousOriginalFilenameDoesNotControlDiskPath() throws Exception {
        StoredObject stored = storage().put(
                new ByteArrayInputStream("safe".getBytes(StandardCharsets.UTF_8)),
                new ObjectMetadata("../../outside.txt", "text/plain")
        );

        assertThat(stored.originalFilename()).isEqualTo("../../outside.txt");
        assertThat(Files.exists(storageRoot.resolve("outside.txt"))).isFalse();
        assertThat(storage().get(stored.objectKey()).readAllBytes())
                .isEqualTo("safe".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 业务目的：已发布文件被替换为符号链接时必须拒绝跟随，防止攻击者借数据库中的合法对象键读取外部文件。
     */
    @Test
    void symbolicLinkObjectIsRejectedOnRead() throws Exception {
        LocalFileObjectStorage storage = storage();
        StoredObject stored = storage.put(
                new ByteArrayInputStream("safe".getBytes(StandardCharsets.UTF_8)),
                new ObjectMetadata("safe.txt", "text/plain")
        );
        Path objectPath = new SafeObjectPathResolver(storageRoot).resolve(stored.objectKey());
        Path outside = Files.createTempFile("loredock-outside-", ".txt");
        Files.delete(objectPath);
        Files.createSymbolicLink(objectPath, outside);

        assertThatThrownBy(() -> storage.get(stored.objectKey()))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(ErrorCode.INVALID_OBJECT_KEY));
    }

    private LocalFileObjectStorage storage() {
        AuditMetadataFactory auditFactory = new AuditMetadataFactory(Clock.fixed(FIXED_TIME, java.time.ZoneOffset.UTC), () -> "SYSTEM");
        return new LocalFileObjectStorage(
                new StorageProperties(storageRoot),
                storedObjectMapper,
                auditFactory,
                Clock.fixed(FIXED_TIME, java.time.ZoneOffset.UTC),
                () -> "SYSTEM"
        );
    }
}
