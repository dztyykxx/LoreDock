package io.github.loredock.code.model;

/**
 * 与操作系统无关的规范仓库相对路径。该值永远不能直接传给 {@code java.nio.file.Path} 解析服务器文件。
 *
 * @param value 使用正斜杠分隔且不含空段、点段或根目录逃逸的路径
 */
public record RepositoryRelativePath(String value) {

    /** 创建拒绝操作系统路径语义和目录逃逸的规范仓库相对路径。 */
    public RepositoryRelativePath {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0
                || value.startsWith("/") || value.endsWith("/") || isDrivePath(value)) {
            throw new IllegalArgumentException("仓库路径必须是规范相对路径");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("仓库路径包含不安全段");
            }
        }
    }

    private static boolean isDrivePath(String value) {
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }
}
