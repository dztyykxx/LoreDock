package io.github.loredock.knowledge.infrastructure.search;

import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingInput;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingPort;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingUnavailableException;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingVector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "loredock.embedding.poc", matches = "true")
class KnowledgeEmbeddingPocTest {

    private static final Path FIXTURE_PATH = Path.of(
            "src/test/resources/knowledge-search-embedding/bge-small-zh-v1.5-reference.json");
    private static final String MODEL_DIRECTORY_PROPERTY = "loredock.embedding.poc.model-dir";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 业务目的：公开参考夹具必须锁定 BGE 的 512 维、CLS pooling、L2 归一化、查询指令和人工可审查排序，
     * 防止 Java PoC 用错误基准自证正确。
     */
    @Test
    void officialReferenceFixtureHasExpectedDimensionPoolingNormalizationAndRanking() throws Exception {
        JsonNode fixture = fixture();
        assertThat(fixture.path("dimension").asInt()).isEqualTo(512);
        assertThat(fixture.path("pooling").asText()).isEqualTo("CLS");
        assertThat(fixture.path("normalized").asBoolean()).isTrue();
        assertThat(fixture.path("queryInstruction").asText()).isEqualTo("为这个句子生成表示以用于检索相关文章：");
        assertThat(fixture.path("passages")).hasSizeBetween(10, 20);
        assertThat(fixture.path("expectedRanking").get(0).asText()).isEqualTo("public-zh-01");
        assertNormalized(vector(fixture.path("instructedQueryVector")));
        fixture.path("passages").forEach(passage -> assertNormalized(vector(passage.path("vector"))));

        System.out.printf(
                "测试证据：场景=BGE官方参考夹具，模型=%s，维度=%d，pooling=%s，公开句子数=%d，Top1=%s%n",
                fixture.path("modelId").asText(), fixture.path("dimension").asInt(),
                fixture.path("pooling").asText(), fixture.path("passages").size(),
                fixture.path("expectedRanking").get(0).asText());
    }

    /**
     * 业务目的：Java 离线适配器必须与官方参考的查询指令、CLS pooling、L2 归一化和批量顺序一致，
     * 防止看似 512 维但语义排序错误的模型进入正式索引。
     */
    @Test
    void localAdapterMatchesOfficialVectorsRankingRepeatedCallsAndBatchOrder() throws Exception {
        JsonNode fixture = fixture();
        Path modelDirectory = modelDirectory();
        KnowledgeEmbeddingPort port = createPort(modelDirectory, fixture.path("modelChecksum").asText());
        Instant startedAt = Instant.now();
        var descriptor = port.describeModel();
        long initializedMillis = Duration.between(startedAt, Instant.now()).toMillis();

        assertThat(descriptor.modelId()).isEqualTo(fixture.path("modelId").asText());
        assertThat(descriptor.checksum()).isEqualTo(fixture.path("modelChecksum").asText());
        assertThat(descriptor.dimension()).isEqualTo(512);

        KnowledgeEmbeddingVector firstQuery = port.embedQuery(fixture.path("query").asText());
        KnowledgeEmbeddingVector repeatedQuery = port.embedQuery(fixture.path("query").asText());
        assertVectorClose(firstQuery.values(), vector(fixture.path("instructedQueryVector")), 0.0002F);
        assertVectorClose(repeatedQuery.values(), firstQuery.values(), 0.000001F);
        assertNormalized(firstQuery.values());

        List<KnowledgeEmbeddingInput> inputs = new ArrayList<>();
        for (int index = 0; index < fixture.path("passages").size(); index++) {
            inputs.add(new KnowledgeEmbeddingInput(
                    new UUID(0L, index + 1L), index, "", List.of(),
                    fixture.path("passages").get(index).path("text").asText()));
        }
        List<KnowledgeEmbeddingVector> actualPassages = port.embedDocuments(inputs);
        assertThat(actualPassages).hasSize(inputs.size());
        for (int index = 0; index < actualPassages.size(); index++) {
            assertVectorClose(
                    actualPassages.get(index).values(),
                    vector(fixture.path("passages").get(index).path("vector")),
                    0.0002F);
        }

        List<RankedPassage> actualRanking = new ArrayList<>();
        for (int index = 0; index < actualPassages.size(); index++) {
            float score = dot(firstQuery.values(), actualPassages.get(index).values());
            actualRanking.add(new RankedPassage(String.format("public-zh-%02d", index + 1), score));
        }
        actualRanking.sort(Comparator.comparingDouble(RankedPassage::score).reversed());
        List<String> rankedIds = actualRanking.stream().map(RankedPassage::id).toList();
        List<String> expectedRanking = new ArrayList<>();
        fixture.path("expectedRanking").forEach(node -> expectedRanking.add(node.asText()));
        assertThat(rankedIds).containsExactlyElementsOf(expectedRanking);

        long beforeBytes = usedHeapBytes();
        Instant latencyStartedAt = Instant.now();
        port.embedQuery(fixture.path("query").asText());
        long warmLatencyMillis = Duration.between(latencyStartedAt, Instant.now()).toMillis();
        long heapDeltaBytes = Math.max(0L, usedHeapBytes() - beforeBytes);
        System.out.printf(
                "测试证据：场景=BGE离线CPU PoC，维度=%d，批量数=%d，初始化毫秒=%d，预热查询毫秒=%d，堆增量字节=%d，Top1=%s%n",
                firstQuery.dimension(), actualPassages.size(), initializedMillis, warmLatencyMillis,
                heapDeltaBytes, rankedIds.get(0));
    }

    /**
     * 业务目的：模型只允许从本地离线资源加载，缺失文件、HTTP 地址或 checksum 不匹配必须明确失败，
     * 防止生产请求期间静默联网下载或使用未经确认的模型。
     */
    @Test
    void localResourcesAndChecksumAreMandatoryWithoutOnlineFallback() throws Exception {
        Path modelDirectory = modelDirectory();
        assertThatThrownBy(() -> createPort(modelDirectory.resolve("missing"), "0".repeat(64)).describeModel())
                .isInstanceOf(KnowledgeEmbeddingUnavailableException.class);
        assertThatThrownBy(() -> createPort(modelDirectory, "0".repeat(64)).describeModel())
                .isInstanceOf(KnowledgeEmbeddingUnavailableException.class);
        assertThatThrownBy(() -> createPort("https://example.test/model.onnx", "https://example.test/tokenizer.json",
                "0".repeat(64)).describeModel())
                .isInstanceOf(KnowledgeEmbeddingUnavailableException.class);

        System.out.println("测试证据：场景=BGE离线资源门禁，缺失资源/checksum不符/HTTP URI 均被明确拒绝");
    }

    private JsonNode fixture() throws Exception {
        return JSON.readTree(Files.readString(FIXTURE_PATH));
    }

    private Path modelDirectory() {
        String configured = System.getProperty(MODEL_DIRECTORY_PROPERTY);
        assertThat(configured).as("显式 PoC 必须提供本地模型目录").isNotBlank();
        Path directory = Path.of(configured).toAbsolutePath().normalize();
        assertThat(directory.resolve("model.onnx")).isRegularFile();
        assertThat(directory.resolve("tokenizer.json")).isRegularFile();
        return directory;
    }

    private KnowledgeEmbeddingPort createPort(Path directory, String checksum) throws Exception {
        return createPort(
                directory.resolve("model.onnx").toUri().toString(),
                directory.resolve("tokenizer.json").toUri().toString(),
                checksum);
    }

    private KnowledgeEmbeddingPort createPort(String modelUri, String tokenizerUri, String checksum) throws Exception {
        try {
            Class<?> propertiesType = Class.forName(
                    "io.github.loredock.knowledge.infrastructure.search.KnowledgeEmbeddingProperties");
            Object properties = propertiesType.getConstructor().newInstance();
            set(propertiesType, properties, "setModelId", "BAAI/bge-small-zh-v1.5");
            set(propertiesType, properties, "setModelUri", modelUri);
            set(propertiesType, properties, "setTokenizerUri", tokenizerUri);
            set(propertiesType, properties, "setChecksum", checksum);
            set(propertiesType, properties, "setOutputName", "sentence_embedding");
            propertiesType.getMethod("setMaxTokens", int.class).invoke(properties, 512);
            set(propertiesType, properties, "setQueryInstruction", "为这个句子生成表示以用于检索相关文章：");
            Class<?> adapterType = Class.forName(
                    "io.github.loredock.knowledge.infrastructure.search.OnnxRuntimeKnowledgeEmbeddingAdapter");
            return (KnowledgeEmbeddingPort) adapterType.getConstructor(propertiesType).newInstance(properties);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("任务 1.4 尚未提供离线 ONNX Runtime 适配器", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw exception;
        }
    }

    private void set(Class<?> type, Object target, String method, String value) throws Exception {
        type.getMethod(method, String.class).invoke(target, value);
    }

    private float[] vector(JsonNode node) {
        float[] values = new float[node.size()];
        for (int index = 0; index < node.size(); index++) {
            values[index] = (float) node.get(index).asDouble();
        }
        return values;
    }

    private void assertVectorClose(float[] actual, float[] expected, float tolerance) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int index = 0; index < actual.length; index++) {
            assertThat(actual[index]).as("vector[%s]", index).isCloseTo(expected[index],
                    org.assertj.core.data.Offset.offset(tolerance));
        }
    }

    private void assertNormalized(float[] vector) {
        assertThat(Math.sqrt(dot(vector, vector))).isCloseTo(1D, org.assertj.core.data.Offset.offset(0.0002D));
    }

    private float dot(float[] left, float[] right) {
        float result = 0F;
        for (int index = 0; index < left.length; index++) {
            result += left[index] * right[index];
        }
        return result;
    }

    private long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private record RankedPassage(String id, float score) {
    }
}
