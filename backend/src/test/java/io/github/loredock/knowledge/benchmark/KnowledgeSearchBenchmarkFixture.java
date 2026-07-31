package io.github.loredock.knowledge.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.model.enums.KnowledgeScopeType;
import io.github.loredock.knowledge.model.enums.KnowledgeSearchMode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** 读取可提交的 T5 知识检索基准材料，不从待评估结果生成问题或正确来源。 */
final class KnowledgeSearchBenchmarkFixture {

    static final String ROOT = "knowledge-search-benchmark/";
    private static final ObjectMapper JSON = new ObjectMapper();

    private KnowledgeSearchBenchmarkFixture() {
    }

    static Fixture load() {
        try {
            Manifest manifest = readJson("manifest.json", Manifest.class);
            QuestionSet questionSet = readJson("questions.json", QuestionSet.class);
            return new Fixture(manifest, questionSet.benchmarkVersion(), questionSet.questions());
        } catch (IOException exception) {
            throw new IllegalStateException("knowledge search benchmark fixture cannot be read", exception);
        }
    }

    static String readText(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")) {
            throw new IllegalArgumentException("benchmark document path is invalid");
        }
        try (InputStream input = new ClassPathResource(ROOT + relativePath).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("benchmark document cannot be read", exception);
        }
    }

    private static <T> T readJson(String name, Class<T> type) throws IOException {
        try (InputStream input = new ClassPathResource(ROOT + name).getInputStream()) {
            return JSON.readValue(input, type);
        }
    }

    record Fixture(Manifest manifest, String questionVersion, List<Question> questions) {
        Fixture {
            questions = List.copyOf(questions);
        }
    }

    record Manifest(
            String benchmarkVersion,
            boolean reviewedByHuman,
            String reviewNote,
            List<Project> projects,
            List<Document> documents
    ) {
        Manifest {
            projects = List.copyOf(projects);
            documents = List.copyOf(documents);
        }
    }

    record Project(Long id, String identifier, String name, List<Branch> branches) {
        Project {
            branches = List.copyOf(branches);
        }
    }

    record Branch(Long id, String name) {
    }

    record Document(
            Long id,
            String file,
            String title,
            DocumentFormat format,
            DocumentStatus status,
            Scope scope,
            List<String> tags,
            DocumentSourceType sourceType
    ) {
        Document {
            tags = List.copyOf(tags);
        }
    }

    record Scope(KnowledgeScopeType type, String project, String branch) {
    }

    record QuestionSet(String benchmarkVersion, List<Question> questions) {
    }

    record Question(
            String id,
            String category,
            String query,
            KnowledgeBrowseContextType contextType,
            String projectIdentifier,
            String branch,
            List<KnowledgeSearchMode> modes,
            QuestionFilters filters,
            boolean semanticParaphrase,
            String paraphraseReview,
            boolean hasAnswer,
            List<Long> expectedDocumentIds,
            String noAnswerReason
    ) {
        Question {
            modes = List.copyOf(modes);
            expectedDocumentIds = List.copyOf(expectedDocumentIds);
        }
    }

    record QuestionFilters(List<String> tags, DocumentFormat format, DocumentSourceType sourceType) {
        QuestionFilters {
            tags = List.copyOf(tags);
        }
    }
}
