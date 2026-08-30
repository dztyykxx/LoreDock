package io.github.loredock.memory.api;

/**
 * 待提炼记忆候选：一条候选对应一句用户偏好表述的提炼结果。
 *
 * @param title 短标题（≤200 码点）
 * @param content 正文（≤4000 码点；判断链未产出摘要时截取正文前 300 码点）
 * @param category 分类，可空；为空按 OTHER 处理
 * @param summary 建议摘要，可空；为空则由判断链从正文截取
 */
public record MemoryCandidate(String title, String content, MemoryCategory category, String summary) {
}
