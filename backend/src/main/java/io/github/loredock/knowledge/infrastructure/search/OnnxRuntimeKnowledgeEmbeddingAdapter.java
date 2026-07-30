package io.github.loredock.knowledge.infrastructure.search;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingInput;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingModelDescriptor;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingPort;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingUnavailableException;
import io.github.loredock.knowledge.application.search.KnowledgeEmbeddingVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于 ONNX Runtime CPU 与 Hugging Face tokenizer 的离线 Embedding 适配器。
 *
 * <p>适配器惰性加载并校验模型，直接消费锁定导出图提供的 {@code sentence_embedding}。该输出已经完成
 * BGE 要求的 CLS pooling 与 L2 归一化，Java 侧只验证契约，不重复实现模型后处理。
 */
public final class OnnxRuntimeKnowledgeEmbeddingAdapter implements KnowledgeEmbeddingPort, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(OnnxRuntimeKnowledgeEmbeddingAdapter.class);
    private static final int EXPECTED_DIMENSION = 512;
    private static final Set<String> LOCAL_SCHEMES = Set.of("file", "classpath");
    private static final float NORMALIZED_TOLERANCE = 0.002F;

    private final KnowledgeEmbeddingProperties properties;
    private final ResourceLoader resourceLoader;
    private volatile RuntimeState runtimeState;

    /**
     * @param properties 服务端受控的离线模型配置；构造过程不会读取模型或触发网络访问
     */
    public OnnxRuntimeKnowledgeEmbeddingAdapter(KnowledgeEmbeddingProperties properties) {
        this(properties, new DefaultResourceLoader());
    }

    OnnxRuntimeKnowledgeEmbeddingAdapter(
            KnowledgeEmbeddingProperties properties,
            ResourceLoader resourceLoader
    ) {
        this.properties = Objects.requireNonNull(properties, "embedding properties are required");
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resource loader is required");
    }

    @Override
    public KnowledgeEmbeddingModelDescriptor describeModel() {
        RuntimeState state = runtime();
        return new KnowledgeEmbeddingModelDescriptor(
                properties.getModelId(), properties.getChecksum().toLowerCase(), state.dimension());
    }

    @Override
    public List<KnowledgeEmbeddingVector> embedDocuments(List<KnowledgeEmbeddingInput> inputs) {
        Objects.requireNonNull(inputs, "embedding inputs are required");
        if (inputs.isEmpty()) {
            return List.of();
        }
        List<String> texts = inputs.stream().map(this::composeDocumentText).toList();
        return embed(texts, "document", inputs.size());
    }

    @Override
    public KnowledgeEmbeddingVector embedQuery(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            throw unavailable(new IllegalArgumentException("query is blank"));
        }
        String instructedQuery = properties.getQueryInstruction() + normalizedQuery;
        return embed(List.of(instructedQuery), "query", 1).getFirst();
    }

    private List<KnowledgeEmbeddingVector> embed(List<String> texts, String operation, int batchSize) {
        long startedAt = System.nanoTime();
        try {
            RuntimeState state = runtime();
            Encoding[] encodings = state.tokenizer().batchEncode(texts);
            long[][] inputIds = new long[encodings.length][];
            long[][] attentionMasks = new long[encodings.length][];
            long[][] tokenTypeIds = new long[encodings.length][];
            for (int index = 0; index < encodings.length; index++) {
                inputIds[index] = encodings[index].getIds();
                attentionMasks[index] = encodings[index].getAttentionMask();
                tokenTypeIds[index] = encodings[index].getTypeIds();
            }
            List<KnowledgeEmbeddingVector> vectors = runModel(
                    state, inputIds, attentionMasks, tokenTypeIds, batchSize);
            LOGGER.info(
                    "knowledge_embedding_completed operation={} modelId={} batchSize={} dimension={} elapsedMs={}",
                    operation, properties.getModelId(), batchSize, state.dimension(), elapsedMillis(startedAt));
            return vectors;
        } catch (KnowledgeEmbeddingUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            LOGGER.error(
                    "knowledge_embedding_failed operation={} modelId={} batchSize={} elapsedMs={} reason={}",
                    operation, safeModelId(), batchSize, elapsedMillis(startedAt),
                    exception.getClass().getSimpleName());
            throw unavailable(exception);
        }
    }

    private List<KnowledgeEmbeddingVector> runModel(
            RuntimeState state,
            long[][] inputIds,
            long[][] attentionMasks,
            long[][] tokenTypeIds,
            int expectedBatchSize
    ) throws OrtException {
        Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
        try {
            inputs.put("input_ids", OnnxTensor.createTensor(state.environment(), inputIds));
            inputs.put("attention_mask", OnnxTensor.createTensor(state.environment(), attentionMasks));
            if (state.inputNames().contains("token_type_ids")) {
                inputs.put("token_type_ids", OnnxTensor.createTensor(state.environment(), tokenTypeIds));
            }
            try (OrtSession.Result result = state.session().run(inputs)) {
                Object value = result.get(properties.getOutputName())
                        .orElseThrow(() -> new OrtException("configured embedding output is missing"))
                        .getValue();
                if (!(value instanceof float[][] vectors) || vectors.length != expectedBatchSize) {
                    throw new OrtException("embedding output shape does not match batch contract");
                }
                List<KnowledgeEmbeddingVector> resultVectors = new ArrayList<>(vectors.length);
                for (float[] vector : vectors) {
                    validateVector(vector);
                    resultVectors.add(new KnowledgeEmbeddingVector(vector));
                }
                return List.copyOf(resultVectors);
            }
        } finally {
            for (OnnxTensor input : inputs.values()) {
                input.close();
            }
        }
    }

    private RuntimeState runtime() {
        RuntimeState current = runtimeState;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (runtimeState == null) {
                runtimeState = initialize();
            }
            return runtimeState;
        }
    }

    private RuntimeState initialize() {
        long startedAt = System.nanoTime();
        validateConfiguration();
        HuggingFaceTokenizer tokenizer = null;
        OrtSession session = null;
        try {
            Resource modelResource = localResource(properties.getModelUri());
            Resource tokenizerResource = localResource(properties.getTokenizerUri());
            byte[] modelBytes = readBytes(modelResource);
            verifyChecksum(modelBytes);
            byte[] tokenizerBytes = readBytes(tokenizerResource);
            tokenizer = HuggingFaceTokenizer.newInstance(
                    new java.io.ByteArrayInputStream(tokenizerBytes),
                    Map.of(
                            "addSpecialTokens", "true",
                            "doLowerCase", "true",
                            "truncation", "true",
                            "padding", "LONGEST",
                            "maxLength", Integer.toString(properties.getMaxTokens()),
                            "modelMaxLength", Integer.toString(properties.getMaxTokens())));

            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            try {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                session = environment.createSession(modelBytes, options);
            } finally {
                options.close();
            }
            if (!session.getOutputNames().contains(properties.getOutputName())) {
                throw new IllegalStateException("configured sentence embedding output is missing");
            }
            if (!session.getInputNames().containsAll(Set.of("input_ids", "attention_mask"))) {
                throw new IllegalStateException("required model inputs are missing");
            }
            RuntimeState initialized = new RuntimeState(
                    environment, session, tokenizer, session.getInputNames(), EXPECTED_DIMENSION);
            LOGGER.info(
                    "knowledge_embedding_initialized modelId={} checksum={} dimension={} elapsedMs={}",
                    properties.getModelId(), checksumPrefix(), EXPECTED_DIMENSION, elapsedMillis(startedAt));
            return initialized;
        } catch (Exception exception) {
            closeQuietly(session);
            closeQuietly(tokenizer);
            LOGGER.error(
                    "knowledge_embedding_initialization_failed modelId={} elapsedMs={} reason={}",
                    safeModelId(), elapsedMillis(startedAt), exception.getClass().getSimpleName());
            throw unavailable(exception);
        }
    }

    private void validateConfiguration() {
        requireText(properties.getModelId(), "model id");
        requireText(properties.getModelUri(), "model uri");
        requireText(properties.getTokenizerUri(), "tokenizer uri");
        requireText(properties.getOutputName(), "output name");
        requireText(properties.getQueryInstruction(), "query instruction");
        if (!properties.getChecksum().matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("model checksum must be SHA-256");
        }
        if (properties.getMaxTokens() < 1 || properties.getMaxTokens() > 512) {
            throw new IllegalArgumentException("max tokens must be between 1 and 512");
        }
    }

    private Resource localResource(String location) {
        String scheme;
        if (location.startsWith("classpath:")) {
            scheme = "classpath";
        } else {
            try {
                scheme = URI.create(location).getScheme();
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("embedding resource URI is invalid", exception);
            }
        }
        if (scheme == null || !LOCAL_SCHEMES.contains(scheme.toLowerCase())) {
            throw new IllegalArgumentException("embedding resource must be local");
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists() || !resource.isReadable()) {
            throw new IllegalArgumentException("embedding resource is unavailable");
        }
        return resource;
    }

    private byte[] readBytes(Resource resource) throws IOException {
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        }
    }

    private void verifyChecksum(byte[] modelBytes) throws NoSuchAlgorithmException {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(modelBytes));
        if (!actual.equalsIgnoreCase(properties.getChecksum())) {
            throw new IllegalArgumentException("embedding model checksum mismatch");
        }
    }

    private String composeDocumentText(KnowledgeEmbeddingInput input) {
        Objects.requireNonNull(input, "embedding input is required");
        List<String> sections = new ArrayList<>();
        if (input.title() != null && !input.title().isBlank()) {
            sections.add(input.title().strip());
        }
        if (input.tags() != null && !input.tags().isEmpty()) {
            sections.add("标签：" + String.join("、", input.tags()));
        }
        if (input.content() != null && !input.content().isBlank()) {
            sections.add(input.content().strip());
        }
        if (sections.isEmpty()) {
            throw unavailable(new IllegalArgumentException("document embedding text is blank"));
        }
        return String.join("\n", sections);
    }

    private void validateVector(float[] vector) {
        if (vector == null || vector.length != EXPECTED_DIMENSION) {
            throw new IllegalStateException("embedding output dimension mismatch");
        }
        double squaredNorm = 0D;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("embedding output contains non-finite value");
            }
            squaredNorm += value * value;
        }
        double norm = Math.sqrt(squaredNorm);
        if (Math.abs(norm - 1D) > NORMALIZED_TOLERANCE) {
            throw new IllegalStateException("embedding output is not L2 normalized");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private String safeModelId() {
        String modelId = properties.getModelId();
        return modelId == null || modelId.isBlank() ? "unconfigured" : modelId;
    }

    private String checksumPrefix() {
        String checksum = properties.getChecksum();
        return checksum == null || checksum.length() < 12 ? "invalid" : checksum.substring(0, 12).toLowerCase();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private KnowledgeEmbeddingUnavailableException unavailable(Throwable cause) {
        return new KnowledgeEmbeddingUnavailableException(cause);
    }

    @Override
    public synchronized void close() {
        RuntimeState state = runtimeState;
        runtimeState = null;
        if (state != null) {
            closeQuietly(state.session());
            closeQuietly(state.tokenizer());
        }
    }

    private void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception exception) {
            LOGGER.warn("knowledge_embedding_resource_close_failed reason={}", exception.getClass().getSimpleName());
        }
    }

    private record RuntimeState(
            OrtEnvironment environment,
            OrtSession session,
            HuggingFaceTokenizer tokenizer,
            Set<String> inputNames,
            int dimension
    ) {
        private RuntimeState {
            inputNames = Set.copyOf(inputNames);
        }
    }
}
