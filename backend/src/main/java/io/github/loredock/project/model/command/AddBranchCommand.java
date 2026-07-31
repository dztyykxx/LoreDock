package io.github.loredock.project.model.command;

/**
 * 在单一项目范围内添加分支的输入。
 *
 * @param name 保留大小写的 Git 风格分支名，长度为 1 至 128
 */
public record AddBranchCommand(String name) {
}
