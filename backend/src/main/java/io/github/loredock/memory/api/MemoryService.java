package io.github.loredock.memory.api;

import java.util.List;

/**
 * 记忆模块对其他模块（当前为 Agent 模块）提供的稳定契约，含管理接口。
 *
 * <p>跨模块只能依赖本接口与 api 包中的不可变类型，不得读取 memory 模块内部
 * 实体、Mapper 或业务服务视图。所有失败均以 {@link MemoryRequestException}
 * 带稳定错误码返回，不得静默。</p>
 *
 * <p>记忆语义约束：记忆是共享的（不按用户隔离）；记忆只表达用户对文档产出的
 * 长期偏好（格式/模板/内容/风格/流程），不得作为知识事实、证据引用或知识检索内容；
 * 记忆的读写不得扩大任何项目、分支、知识或发布范围。</p>
 */
public interface MemoryService {

    /**
     * 摘要级检索/预载：仅覆盖 {@code ACTIVE} 且范围为「{@code GLOBAL} ∪ 指定项目」的记忆。
     *
     * <p>按查询词对标题/摘要/正文做全文匹配，加使用频次做确定性打分排序；
     * 有界返回摘要（每条 ≤300 码点），硬上限 30 条；无命中时返回最近使用的高频记忆
     * 兜底（不超过 3 条，仍遵守范围隔离）。查询词由调用方提供且已限长。</p>
     *
     * @param query 检索请求（范围与上限）
     * @return 有界摘要列表；无任何可用记忆时为空列表
     */
    List<MemoryRelevant> listRelevant(MemoryRelevantQuery query);

    /**
     * 全文按需加载：校验记忆可达（{@code GLOBAL} 或属于请求会话项目）后返回完整正文，
     * 加载成功递增使用频次并刷新最近使用时间；不可达拒答且频次不变。
     *
     * @param memoryId 记忆编号
     * @param projectId 请求会话归属项目；为空表示 GLOBAL 侧会话
     * @return 记忆完整视图（正文 ≤4000 码点）
     * @throws MemoryRequestException 不存在时 {@code MEMORY_NOT_FOUND}；
     *         可达性校验失败时 {@code MEMORY_SCOPE_VIOLATION}
     */
    MemoryFull loadFull(Long memoryId, Long projectId);

    /**
     * 提炼写入：对候选逐条执行「值得写 / 语义重复 / 冲突仍写」判断后写入。
     *
     * <p>范围由请求自身决定（会话挂项目→PROJECT，否则 GLOBAL），调用方不得指定；
     * 语义重复（含 DISABLED）跳过且不复活；语义冲突仍写入、两条均保持 ACTIVE；
     * 单 run（{@code sourceRunId}）累计新写数量达到上限（默认 10）后整体拒写。
     * 判断模型失败抛出 {@code MEMORY_JUDGE_UNAVAILABLE}，不产生无判断记录。</p>
     *
     * @param request 提炼请求（候选 1~3 条、带来源与操作者）
     * @return 与候选一一对应的判断结论（按候选下标）
     * @throws MemoryRequestException 字段或数量非法 {@code MEMORY_FIELD_INVALID}；
     *         项目无效 {@code MEMORY_PROJECT_INVALID}；预算超限 {@code MEMORY_BUDGET_EXCEEDED}；
     *         判断模型不可用 {@code MEMORY_JUDGE_UNAVAILABLE}
     */
    List<MemoryWriteVerdict> acceptWrite(MemoryWriteInput request);

    /**
     * 管理列表：按范围/分类/状态/关键词过滤并分页，登录即可访问。
     *
     * @param query 过滤与分页请求
     * @return 有界分页结果（管理视图，含正文）
     */
    MemoryPage listPage(MemoryPageQuery query);

    /**
     * 人工创建记忆（MANUAL 来源）：范围与所属项目必须显式给定，
     * PROJECT 时项目必须存在且启用；不做语义判断，但字段与 scope 校验不可绕过。
     *
     * @param command 创建命令
     * @return 创建后的记忆完整视图
     * @throws MemoryRequestException 项目不存在/停用 {@code MEMORY_PROJECT_INVALID}；
     *         字段非法 {@code MEMORY_FIELD_INVALID}
     */
    MemoryFull create(MemoryDraftInput command);

    /**
     * 人工编辑记忆：只允许修改分类、标题、摘要、正文与状态；
     * 范围与所属项目不可编辑，尝试修改按 {@code MEMORY_SCOPE_EDIT_FORBIDDEN} 拒绝。
     *
     * @param command 编辑命令（缺省字段保持原值）
     * @return 更新后的记忆完整视图
     * @throws MemoryRequestException 不存在 {@code MEMORY_NOT_FOUND}；
     *         修改范围/所属项目 {@code MEMORY_SCOPE_EDIT_FORBIDDEN}；字段非法 {@code MEMORY_FIELD_INVALID}
     */
    MemoryFull update(MemoryEditInput command);

    /**
     * 停用/启用记忆：停用后检索与加载不可见，记录保留、可重新启用。
     *
     * @param memoryId 记忆编号
     * @param status 目标状态
     * @param operatorId 操作者（审计）
     * @return 更新后的记忆完整视图
     * @throws MemoryRequestException 不存在 {@code MEMORY_NOT_FOUND}
     */
    MemoryFull setStatus(Long memoryId, MemoryStatus status, String operatorId);

    /**
     * 删除记忆：仅管理员入口调用；删除后的编号不可再被加载。
     *
     * @param memoryId 记忆编号
     * @throws MemoryRequestException 不存在 {@code MEMORY_NOT_FOUND}
     */
    void delete(Long memoryId);
}
