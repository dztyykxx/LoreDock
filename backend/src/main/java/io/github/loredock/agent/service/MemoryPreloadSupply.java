package io.github.loredock.agent.service;

import io.github.loredock.memory.api.MemoryRelevant;
import io.github.loredock.memory.api.MemoryRelevantQuery;
import io.github.loredock.memory.api.MemoryService;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 用户记忆预载快照供应：以 {@code runId} 为键把「一次 run 内主 Agent 注入的记忆摘要集合」固定下来。
 *
 * <p>前缀稳定性：一次用户请求内主 Agent 会被多次组装（{@code START→prep_main}、子图完成后
 * {@code set_main_resume→prep_main} 汇总、恢复重试），记忆块 SHALL 在 run 首次主 Agent 组装时
 * 一次性检索并按 runId 固定；同 run 内再次组装复用同一快照。run 中发生的记忆读写（频次变化、
 * 新增记忆）不得改变当前 run 前缀——快照只在下一 run 重新计算。检索失败 WARN 后返回空列表
 * 跳过注入，不阻塞主链路。</p>
 *
 * <p>生命周期：run 进入终态时由执行器调用 {@link #evict(Long)} 逐出（恢复/暂停保留快照，
 * 同 run 可续跑）；未逐出的条目按惰性 TTL 过期（仅在再次访问时检查，作为进程重启与异常
 * 路径的后备清理，正常情况下 run 终态即清理）。</p>
 */
@Component
public class MemoryPreloadSupply {

    /** 预载目标上限（与记忆模块硬上限一致；服务端按配置收紧）。 */
    static final int PRELOAD_TARGET = 30;

    /** 快照正常有效期（惰性 TTL）；正常路径由 run 终态逐出，TTL 只作后备清理。 */
    static final java.time.Duration TTL = java.time.Duration.ofMinutes(30);

    /** 检索失败快照的短期 TTL：保留「跳过注入」结果，避免同一 run 反复重试并重复 WARN。 */
    static final java.time.Duration FAILURE_TTL = java.time.Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(MemoryPreloadSupply.class);

    private final MemoryService memories;
    private final ConcurrentHashMap<Long, Snapshot> cache = new ConcurrentHashMap<>();

    /**
     * @param memories 记忆契约（跨模块只依赖 {@code memory.api}）
     */
    public MemoryPreloadSupply(MemoryService memories) {
        this.memories = Objects.requireNonNull(memories, "memory service");
    }

    /**
     * 取该 run 固定的记忆摘要快照：首次访问按「原始目标 + 本轮指令」检索并缓存，
     * 同 run 再次访问返回相同快照；检索失败返回空列表（跳过注入）。
     *
     * @param runId 当前 run（快照键）
     * @param projectId 会话归属项目；空表示 GLOBAL 侧会话（只检 GLOBAL 记忆）
     * @param queryWords 查询词（原始目标 + 最近用户消息，调用方已限长）
     * @return 不可变摘要列表；无可用记忆或检索失败时为空列表
     */
    public List<MemoryRelevant> snapshot(Long runId, Long projectId, List<String> queryWords) {
        Snapshot cached = cache.get(runId);
        if (cached != null && System.nanoTime() < cached.expiresAtNanos()) {
            return cached.entries();
        }
        Snapshot fresh = load(runId, projectId, queryWords);
        cache.put(runId, fresh);
        return fresh.entries();
    }

    /**
     * 显式逐出该 run 的快照：run 进入终态时调用，保证下次同编号 run（编号不会复用，可能)
     * 重新计算；恢复与暂停续跑不调用，保持前缀稳定。
     */
    public void evict(Long runId) {
        if (runId != null) {
            cache.remove(runId);
        }
    }

    private Snapshot load(Long runId, Long projectId, List<String> queryWords) {
        try {
            List<String> words = queryWords == null ? List.of() : List.copyOf(queryWords);
            List<MemoryRelevant> entries = memories.listRelevant(
                    new MemoryRelevantQuery(words, projectId, PRELOAD_TARGET));
            log.info("用户记忆预载 runId={} projectId={} 查询词={} 命中={}（快照按 run 固定）",
                    runId, projectId, words, entries.size());
            return new Snapshot(List.copyOf(entries), System.nanoTime() + TTL.toNanos());
        } catch (RuntimeException exception) {
            // 检索失败不阻塞主链路：只跳过本 run 的记忆注入，记住短 TTL 结果避免反复重试。
            log.warn("用户记忆预载失败（跳过注入，不阻塞主链路）runId={} error={}",
                    runId, bounded(String.valueOf(exception.getMessage()), 200));
            return new Snapshot(List.of(), System.nanoTime() + FAILURE_TTL.toNanos());
        }
    }

    private static String bounded(String value, int limit) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }

    /** 缓存条目：条目集合 + 惰性过期时间点（nanoTime 单调，不受时钟回拨影响）。 */
    private record Snapshot(List<MemoryRelevant> entries, long expiresAtNanos) {
    }
}
