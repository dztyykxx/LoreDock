package io.github.loredock.knowledge.benchmark;

import io.github.loredock.knowledge.application.KnowledgeBrowseContextType;
import io.github.loredock.knowledge.application.search.KnowledgeSearchMode;
import io.github.loredock.knowledge.domain.DocumentStatus;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 在任何检索调用前校验基准构成、人工标注、范围可见性和常见敏感模式。 */
final class KnowledgeSearchBenchmarkFixtureValidator {

    private static final Set<KnowledgeSearchMode> ALL_MODES = EnumSet.allOf(KnowledgeSearchMode.class);
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)(password|passwd|token|secret|api[_-]?key)\\s*[:=]\\s*[^\\s]{6,}"),
            Pattern.compile("(?i)https?://[^\\s/]+\\.(internal|corp|local)(?:[/\\s]|$)"),
            Pattern.compile("(?<!\\d)(?:10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})(?!\\d)")
    );

    void validate(KnowledgeSearchBenchmarkFixture.Fixture fixture) {
        require(fixture != null, "benchmark fixture is required");
        var manifest = fixture.manifest();
        require(text(manifest.benchmarkVersion()), "benchmark version is required");
        require(manifest.reviewedByHuman(), "benchmark fixture requires human review");
        require(text(manifest.reviewNote()), "benchmark human review note is required");
        require(manifest.benchmarkVersion().equals(fixture.questionVersion()), "benchmark versions must match");
        validateProjects(manifest.projects());
        Map<UUID, KnowledgeSearchBenchmarkFixture.Document> documents = validateDocuments(manifest.documents());
        validateQuestions(fixture.questions(), documents, manifest.projects());
    }

    private void validateProjects(List<KnowledgeSearchBenchmarkFixture.Project> projects) {
        require(projects.size() == 2, "benchmark must contain two simulated projects");
        Set<UUID> ids = new HashSet<>();
        Set<String> identifiers = new HashSet<>();
        for (var project : projects) {
            require(project.id() != null && ids.add(project.id()), "project IDs must be unique");
            require(text(project.identifier()) && identifiers.add(project.identifier()),
                    "project identifiers must be unique");
            Set<String> branches = new HashSet<>();
            project.branches().forEach(branch -> require(branch.id() != null && text(branch.name())
                    && branches.add(branch.name()), "project branches must be complete and unique"));
            require(branches.containsAll(Set.of("main", "demo")), "each benchmark project needs main and demo");
        }
    }

    private Map<UUID, KnowledgeSearchBenchmarkFixture.Document> validateDocuments(
            List<KnowledgeSearchBenchmarkFixture.Document> entries
    ) {
        require(entries.size() >= 10, "benchmark needs representative documents");
        Map<UUID, KnowledgeSearchBenchmarkFixture.Document> documents = new HashMap<>();
        Set<String> files = new HashSet<>();
        Set<DocumentStatus> states = EnumSet.noneOf(DocumentStatus.class);
        Set<KnowledgeScopeType> scopes = EnumSet.noneOf(KnowledgeScopeType.class);
        for (var document : entries) {
            require(document.id() != null && documents.put(document.id(), document) == null,
                    "document IDs must be unique");
            require(text(document.file()) && files.add(document.file()), "document files must be unique");
            require(text(document.title()) && document.format() != null && document.sourceType() != null,
                    "document metadata must be complete");
            require(document.scope() != null && document.scope().type() != null, "document scope is required");
            validateDocumentScope(document.scope());
            states.add(document.status());
            scopes.add(document.scope().type());
            validateSensitive(document.title());
            validateSensitive(KnowledgeSearchBenchmarkFixture.readText(document.file()));
        }
        require(states.containsAll(EnumSet.allOf(DocumentStatus.class)),
                "benchmark must contain published, draft and archived documents");
        require(scopes.containsAll(EnumSet.allOf(KnowledgeScopeType.class)),
                "benchmark must contain global, project and branch scopes");
        return Map.copyOf(documents);
    }

    private void validateQuestions(
            List<KnowledgeSearchBenchmarkFixture.Question> questions,
            Map<UUID, KnowledgeSearchBenchmarkFixture.Document> documents,
            List<KnowledgeSearchBenchmarkFixture.Project> projects
    ) {
        require(questions.size() >= 15 && questions.size() <= 20,
                "benchmark question count must be between 15 and 20");
        Set<String> questionIds = new HashSet<>();
        long scenarioCount = questions.stream().filter(question -> "SCENARIO_PACK".equals(question.category())).count();
        long projectCount = questions.stream().filter(question -> "PROJECT_GENERAL".equals(question.category())).count();
        long noAnswerCount = questions.stream().filter(question -> !question.hasAnswer()).count();
        long paraphraseCount = questions.stream().filter(KnowledgeSearchBenchmarkFixture.Question::semanticParaphrase)
                .count();
        require(scenarioCount == 10, "benchmark needs ten scenario-pack questions");
        require(projectCount >= 5, "benchmark needs at least five project/general questions");
        require(noAnswerCount >= 3, "benchmark needs at least three no-answer questions");
        require(paraphraseCount >= 2, "benchmark needs at least two semantic paraphrases");

        for (var question : questions) {
            require(text(question.id()) && questionIds.add(question.id()), "question IDs must be unique");
            require(text(question.query()) && question.contextType() != null, "question context must be complete");
            require(new HashSet<>(question.modes()).equals(ALL_MODES), "each question must run all search modes");
            require(question.filters() != null && question.filters().tags().size() <= 10,
                    "each question must record valid shared filters");
            validateQuestionContext(question, projects);
            validateSensitive(question.query());
            validateSensitive(question.noAnswerReason());
            if (question.semanticParaphrase()) {
                require(text(question.paraphraseReview()), "semantic paraphrase needs independent review note");
            }
            if (question.hasAnswer()) {
                require(!question.expectedDocumentIds().isEmpty(), "answerable question needs expected evidence");
                for (UUID expectedId : question.expectedDocumentIds()) {
                    var document = documents.get(expectedId);
                    require(document != null && document.status() == DocumentStatus.PUBLISHED,
                            "expected evidence must reference a published fixture document");
                    require(visible(question, document), "expected evidence must be visible in question scope");
                }
            } else {
                require(question.expectedDocumentIds().isEmpty() && text(question.noAnswerReason()),
                        "no-answer question needs an empty expectation and an explicit reason");
            }
        }
    }

    private void validateDocumentScope(KnowledgeSearchBenchmarkFixture.Scope scope) {
        switch (scope.type()) {
            case GLOBAL -> require(scope.project() == null && scope.branch() == null,
                    "global document cannot contain project scope");
            case PROJECT -> require(text(scope.project()) && scope.branch() == null,
                    "project document needs project only");
            case BRANCH -> require(text(scope.project()) && text(scope.branch()),
                    "branch document needs project and branch");
        }
    }

    private void validateQuestionContext(
            KnowledgeSearchBenchmarkFixture.Question question,
            List<KnowledgeSearchBenchmarkFixture.Project> projects
    ) {
        if (question.contextType() == KnowledgeBrowseContextType.GLOBAL) {
            require(question.projectIdentifier() == null && question.branch() == null,
                    "global question cannot contain project scope");
            return;
        }
        require(text(question.projectIdentifier()) && text(question.branch()),
                "project question needs project and branch");
        require(projects.stream().anyMatch(project -> project.identifier().equals(question.projectIdentifier())
                        && project.branches().stream().anyMatch(branch -> branch.name().equals(question.branch()))),
                "question project branch must exist in manifest");
    }

    private boolean visible(
            KnowledgeSearchBenchmarkFixture.Question question,
            KnowledgeSearchBenchmarkFixture.Document document
    ) {
        if (question.contextType() == KnowledgeBrowseContextType.GLOBAL) {
            return document.scope().type() == KnowledgeScopeType.GLOBAL;
        }
        return switch (document.scope().type()) {
            case GLOBAL -> true;
            case PROJECT -> question.projectIdentifier().equals(document.scope().project());
            case BRANCH -> question.projectIdentifier().equals(document.scope().project())
                    && question.branch().equals(document.scope().branch());
        };
    }

    private void validateSensitive(String value) {
        if (value == null) {
            return;
        }
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            require(!pattern.matcher(value).find(), "benchmark fixture contains a sensitive pattern");
        }
    }

    private boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
