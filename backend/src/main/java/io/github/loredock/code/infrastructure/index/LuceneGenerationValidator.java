package io.github.loredock.code.infrastructure.index;

import io.github.loredock.code.application.CodeGenerationBuildRequest;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** 关闭后重新打开 Lucene generation，验证文件计数、唯一路径和全部身份字段。 */
public class LuceneGenerationValidator implements GenerationIndexValidator {

    @Override
    public void validate(Path directoryPath, CodeGenerationBuildRequest expected) throws IOException {
        try (FSDirectory directory = FSDirectory.open(directoryPath);
             DirectoryReader reader = DirectoryReader.open(directory)) {
            if (reader.numDocs() != expected.files().size() || reader.hasDeletions()) {
                throw new CodeGenerationValidationException("generation document count mismatch");
            }
            Set<String> paths = new HashSet<>();
            for (int documentId = 0; documentId < reader.maxDoc(); documentId++) {
                var document = reader.storedFields().document(documentId);
                require(document.get(CodeIndexFields.PROJECT_ID), expected.projectId().toString(), "project");
                require(document.get(CodeIndexFields.BRANCH_ID), expected.branchId().toString(), "branch");
                require(document.get(CodeIndexFields.SNAPSHOT_ID), expected.snapshotId().toString(), "snapshot");
                require(document.get(CodeIndexFields.GENERATION_ID), expected.generationId().toString(), "generation");
                require(document.get(CodeIndexFields.COMMIT), expected.commit(), "commit");
                String path = document.get(CodeIndexFields.PATH_EXACT);
                if (path == null || !paths.add(path)) {
                    throw new CodeGenerationValidationException("generation path is missing or duplicate");
                }
            }
            Set<String> expectedPaths = expected.files().stream()
                    .map(io.github.loredock.code.application.CodeGenerationFile::path)
                    .collect(java.util.stream.Collectors.toSet());
            if (!paths.equals(expectedPaths)) {
                throw new CodeGenerationValidationException("generation path set mismatch");
            }
        }
    }

    private void require(String actual, String expected, String field) {
        if (!expected.equals(actual)) {
            throw new CodeGenerationValidationException("generation " + field + " scope mismatch");
        }
    }
}
