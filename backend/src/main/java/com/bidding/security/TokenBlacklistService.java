package com.bidding.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JWT 撤销黑名单。
 *
 * <p>为什么要它：JWT 是无状态的，签发后在 {@code exp} 之前一直有效。用户点「退出登录」
 * 时只删浏览器里的 token 是没用的 —— 被 XSS 偷走 / 被日志抓到的旧 token 依然能打通接口。
 * 所以登出必须让服务端记住「这枚 token 已作废」。
 *
 * <p>实现取舍（重要）：这里是单机内存实现
 * （{@code ConcurrentHashMap<token, expMillis>}），进程重启即清空 ——
 * 重启后所有「已登出」的旧 token 会重新变成有效，直到它们自己的 {@code exp} 到期。
 * 这是本课程设计没有 Redis 的已知取舍：优点是零外部依赖、登出立即生效；
 * 代价是①重启丢撤销状态 ②多实例部署时各实例黑名单不共享（A 实例撤销的 token 在 B 实例仍可用）。
 * 生产做法应换成 Redis：{@code SET revoked:<jti> 1 EX <剩余秒数>}，天然带 TTL 且多实例共享。
 *
 * <p>为什么不直接缩短 JWT 过期时间：那会让所有正常用户频繁掉线，属于「影响已经有的」，
 * 所以仍保留 24h，靠黑名单精确撤销「已登出」的那一部分。
 *
 * <p>为什么不引 jti：现有 token 里没有 jti 声明，改 payload 会牵动 {@link JwtUtil} 与已签发令牌；
 * 直接以「token 原文」为 key 等价有效（同一枚 token 的字符串恒定），改动面最小。
 *
 * <p>清理策略：惰性清理，不起定时线程（避免 Bean 生命周期 / 线程泄漏问题）——
 * ①每次查询先看命中的那条是否过期，过期就地删除；
 * ②表规模超过 {@link #CLEANUP_SIZE_TRIGGER} 时做一次全表扫描，清掉所有已过期条目。
 * 因为每条记录带 {@code exp}，最坏情况下表的大小上限 = 未过期的已登出 token 数，不会无限膨胀。
 */
@Slf4j
@Service
public class TokenBlacklistService {

    /** 触发一次全表扫描清理的规模阈值 */
    private static final int CLEANUP_SIZE_TRIGGER = 256;

    /** token -> 该 token 自身的过期时间戳（毫秒）。过期后条目即无意义，可安全丢弃。 */
    private final Map<String, Long> blacklist = new ConcurrentHashMap<>();

    /** 上次全表清理时间（节流用，避免高频登出时反复全扫） */
    private final AtomicLong lastCleanupAt = new AtomicLong(System.currentTimeMillis());

    /**
     * 把一枚 token 加入黑名单。
     *
     * @param token      token 原文（Bearer 后面的那串）
     * @param expMillis  token 自身的过期时间戳（毫秒）。到点后条目自动失效，避免永久占内存。
     */
    public void blacklist(String token, long expMillis) {
        if (token == null || token.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        // exp 已经过去了（或解析不出）说明 token 本来就无效，没必要占坑
        long effectiveExp = expMillis > now ? expMillis : now;
        blacklist.put(token, effectiveExp);
        if (blacklist.size() > CLEANUP_SIZE_TRIGGER) {
            cleanupExpired(now);
        }
    }

    /**
     * 判断 token 是否已被撤销。
     *
     * <p>惰性清理：命中的条目若已过期，顺手删掉并返回 false（该 token 已由 exp 自然失效）。
     */
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        Long exp = blacklist.get(token);
        if (exp == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (exp <= now) {
            blacklist.remove(token, exp);
            return false;
        }
        return true;
    }

    /** 清掉所有已过期的条目；60s 内最多扫一次 */
    private void cleanupExpired(long now) {
        long last = lastCleanupAt.get();
        if (now - last < 60_000L) {
            return;
        }
        // CAS 抢锁：并发登出时只让一个线程扫表
        if (!lastCleanupAt.compareAndSet(last, now)) {
            return;
        }
        int removed = 0;
        for (Iterator<Map.Entry<String, Long>> it = blacklist.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("令牌黑名单惰性清理：移除 {} 条，剩余 {} 条", removed, blacklist.size());
        }
    }
}
