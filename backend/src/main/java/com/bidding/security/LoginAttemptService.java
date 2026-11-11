package com.bidding.security;

import com.bidding.common.BusinessException;
import com.bidding.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 登录防爆破 / 限流。关键点：JWT 无状态不意味着认证入口不用防暴力破解。
 *
 * <p>三个维度（时间窗都是 15 分钟）：
 * <ol>
 *   <li>账号维度 {@code 用户名|IP}：连续失败 5 次 → 该组合锁 15 分钟（原有行为，未放宽）。
 *       这一条同时兜住了「定点爆破 admin」—— 代价是知道用户名的人能定向锁死它，属已知取舍（防爆破优先）。</li>
 *   <li>IP 维度：同一 IP 对任意用户名累计失败 20 次 → 锁该 IP 15 分钟。
 *       补掉原来的绕过口子：换用户名就能重置计数（撞库常用手法）。</li>
 *   <li>全局维度：全局累计失败 100 次 → 15 分钟内拒绝新尝试。
 *       兜底「分布式/多 IP 撞库把 CPU 打满」这类不针对特定账号的洪水。不做回环豁免。</li>
 * </ol>
 *
 * <p>回环豁免：{@code ip} 是回环地址时，IP 维度既不计数也不检查，
 * 账号维度与全局维度照旧生效。理由：
 * <ul>
 *   <li>本项目前端走 {@code vue.config.js} 的 dev-server 代理，Java 侧 {@code getRemoteAddr()}
 *       恒为 {@code 127.0.0.1}，此时 IP 维度实际退化成「全局维度」：本机误输 20 次密码会把
 *       本机所有账号锁 15 分钟，只能重启后端恢复，体验不可接受。</li>
 *   <li>回环地址不可能是「外部撞库来源」——能发回环请求的进程已经在这台机器上了，
 *       那时限流也挡不住什么，所以豁免它不损失这一维度的安全价值。</li>
 *   <li>生产环境后端拿到的是真实客户端 IP（非回环），IP 维度照常生效，
 *       跨用户名计数这类撞库防护没有被削弱。</li>
 * </ul>
 *
 * <p>失败 key 的 TTL 清理：每条记录带 {@code lastFailureAt}，超过 30 分钟没有新失败的条目
 * 会被清掉（{@link #maybeCleanup(long)}：60s 节流 + 表规模超阈值立即触发）。
 * 原来的实现只增不减，长期运行会被随机用户名刷爆内存（OOM 面），这是本轮的修复点之一。
 *
 * <p>仍然是单机内存实现：重启即清空，多实例不共享。生产应换 Redis
 * （INCR + EXPIRE，维度 key 同下面三种；或者直接在网关做限流）。
 *
 * <p>公开方法签名保持不变（{@link #checkBlocked(String)} / {@link #onFailure(String)} /
 * {@link #onSuccess(String)}），key 仍由 {@code AuthServiceImpl} 按「用户名 + "|" + IP」拼，
 * 所以 IP / 全局维度在内部从同一个 key 反解，无需改业务侧调用。
 */
@Slf4j
@Service
public class LoginAttemptService {

    /** ①单账号（用户名|IP）连续失败阈值 */
    private static final int MAX_ATTEMPTS = 5;
    /** ②单 IP 对任意用户名的失败阈值 */
    private static final int MAX_IP_ATTEMPTS = 20;
    /** ③全局失败阈值 */
    private static final int MAX_GLOBAL_ATTEMPTS = 100;

    /** 统计窗口 / 锁定时长：15 分钟 */
    private static final long WINDOW_MS = 15 * 60 * 1000L;
    /** 失败条目 TTL：30 分钟无新失败即回收（防 OOM） */
    private static final long ENTRY_TTL_MS = 30 * 60 * 1000L;
    /** 全表清理节流间隔 */
    private static final long CLEANUP_INTERVAL_MS = 60 * 1000L;
    /** 表规模超过该值立即触发一次清理（不等节流） */
    private static final int CLEANUP_SIZE_TRIGGER = 500;

    /** 全局维度的固定 key（前缀 g: 与账号/IP 维度区分，避免用户名撞车） */
    private static final String GLOBAL_KEY = "g:#global";
    /** 账号维度前缀 */
    private static final String USER_PREFIX = "u:";
    /** IP 维度前缀 */
    private static final String IP_PREFIX = "ip:";

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupAt = new AtomicLong(System.currentTimeMillis());

    /**
     * 校验是否处于锁定状态。三个维度任一中招都直接抛 429
     * （沿用既有语义：锁定/限流用 {@link ResultCode#TOO_MANY_REQUESTS}）。
     */
    public void checkBlocked(String key) {
        long now = System.currentTimeMillis();

        // ③ 全局：最粗的一道闸，先判，省掉后面两次 lookup
        Attempt global = attempts.get(GLOBAL_KEY);
        if (global != null && global.isBlocked(now)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS,
                    "登录尝试过于频繁，请稍后再试");
        }

        // ② IP：同一出口 IP 换用户名也逃不掉。回环地址豁免（见类注释「回环豁免」）：
        //    本机 dev 代理下 IP 恒为 127.0.0.1，若还检查这一维，本机误输就会把自己锁死。
        String ip = ipOf(key);
        if (!isLoopback(ip)) {
            Attempt ipAttempt = attempts.get(IP_PREFIX + ip);
            if (ipAttempt != null && ipAttempt.isBlocked(now)) {
                throw new BusinessException(ResultCode.TOO_MANY_REQUESTS,
                        "该网络地址登录失败次数过多，请稍后再试");
            }
        }

        // ① 账号维度（保持原有提示文案不变，避免影响前端既有提示）
        Attempt attempt = attempts.get(USER_PREFIX + key);
        if (attempt != null && attempt.isBlocked(now)) {
            throw new BusinessException(ResultCode.TOO_MANY_REQUESTS,
                    "尝试次数过多，请稍后再试");
        }
    }

    /** 记录一次失败，任一维度达到阈值即进入锁定 */
    public void onFailure(String key) {
        long now = System.currentTimeMillis();
        maybeCleanup(now);

        String ip = ipOf(key);

        // ① 账号维度：5 次 → 锁（原有阈值，不放宽）
        boolean userLocked = hit(USER_PREFIX + key, MAX_ATTEMPTS, now);
        if (userLocked) {
            log.warn("[登录锁定] 账号维度触发：key={}（username={} ip={}）已锁定 {} 分钟；"
                    + "如需立即恢复请重启后端或等锁定到期（本系统不提供解锁 UI）",
                    key, usernameOf(key), ip, WINDOW_MS / 60000);
            // admin 被锁要更醒目的告警：可能是爆破，也可能是运维误操作
            if ("admin".equalsIgnoreCase(usernameOf(key))) {
                log.warn("[安全告警] 管理员账号 admin 因连续登录失败被锁定，"
                        + "来源 IP={}；若不是本人操作请按爆破事件排查", ip);
            }
        }

        // ② IP 维度：20 次（跨用户名累计）→ 锁该 IP
        // 回环地址豁免：本机 dev 代理（webpack / 直连）下 getRemoteAddr 恒为 127.0.0.1，
        // 不豁免的话本机误输 20 次会把所有人锁死 15 分钟。生产由网关传真实 IP 时不受影响。
        if (!isLoopback(ip) && hit(IP_PREFIX + ip, MAX_IP_ATTEMPTS, now)) {
            log.warn("[登录锁定] IP 维度触发：ip={} 在 {} 分钟内失败 {} 次（多用户名累计），已锁定 {} 分钟",
                    ip, WINDOW_MS / 60000, MAX_IP_ATTEMPTS, WINDOW_MS / 60000);
        }

        // ③ 全局维度：100 次 → 熔断 15 分钟
        if (hit(GLOBAL_KEY, MAX_GLOBAL_ATTEMPTS, now)) {
            log.warn("[登录锁定] 全局维度触发：{} 分钟内累计失败 {} 次，已熔断 {} 分钟（防撞库打满）",
                    WINDOW_MS / 60000, MAX_GLOBAL_ATTEMPTS, WINDOW_MS / 60000);
        }
    }

    /** 登录成功后清除该「用户名|IP」记录（IP / 全局计数不清：那是安全兜底，不能靠一次成功抹掉） */
    public void onSuccess(String key) {
        attempts.remove(USER_PREFIX + key);
    }

    /**
     * 记一次失败：窗口内累加，到阈值就锁定并清零计数。
     *
     * @return 本次是否刚好触发锁定
     */
    private boolean hit(String mapKey, int threshold, long now) {
        Attempt attempt = attempts.computeIfAbsent(mapKey, k -> new Attempt());
        return attempt.fail(now, WINDOW_MS, threshold);
    }

    /** 从「用户名|IP」里反解 IP（AuthServiceImpl 固定用 '|' 拼接，用户名不含 '|'） */
    private static String ipOf(String key) {
        if (key == null) {
            return "unknown";
        }
        int i = key.lastIndexOf('|');
        return (i < 0 || i == key.length() - 1) ? "unknown" : key.substring(i + 1);
    }

    /**
     * 回环地址判定：127.0.0.0/8、::1、0:0:0:0:0:0:0:1、::ffff:127.x（IPv4-mapped）、localhost。
     * 用字符串匹配而不是 {@code InetAddress.getByName()}：后者会触发 DNS 解析、还可能抛
     * {@code UnknownHostException}；而 {@code getRemoteAddr()} 返回的就是点分/冒分字符串。
     */
    private static boolean isLoopback(String ip) {
        if (ip == null) {
            return false;
        }
        String v = ip.trim();
        return "127.0.0.1".equals(v)
                || "::1".equals(v)
                || "0:0:0:0:0:0:0:1".equals(v)
                || "[::1]".equals(v)
                || v.startsWith("127.")
                || v.startsWith("::ffff:127.")
                || "localhost".equalsIgnoreCase(v);
    }

    /** 从「用户名|IP」里反解用户名（仅用于日志） */
    private static String usernameOf(String key) {
        if (key == null) {
            return "unknown";
        }
        int i = key.lastIndexOf('|');
        return i <= 0 ? key : key.substring(0, i);
    }

    /**
     * TTL 清理：删掉「已过锁定期且超过 30 分钟没有新失败」的条目。
     * 60s 节流；表规模超 {@link #CLEANUP_SIZE_TRIGGER} 时不等节流立即扫。
     */
    private void maybeCleanup(long now) {
        long last = lastCleanupAt.get();
        boolean bySize = attempts.size() > CLEANUP_SIZE_TRIGGER;
        if (!bySize && now - last < CLEANUP_INTERVAL_MS) {
            return;
        }
        // CAS 抢锁：并发失败请求时只让一个线程扫表
        if (!lastCleanupAt.compareAndSet(last, now)) {
            return;
        }
        int removed = 0;
        for (Iterator<Map.Entry<String, Attempt>> it = attempts.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Attempt> e = it.next();
            if (e.getValue().isStale(now, ENTRY_TTL_MS)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("登录失败计数 TTL 清理：移除 {} 条，剩余 {} 条", removed, attempts.size());
        }
    }

    /**
     * 单个维度的计数条目。
     * 字段只在 {@code synchronized} 方法里读写 —— 并发登录时不会出现「计数丢失」。
     */
    private static class Attempt {
        /** 窗口内失败次数 */
        private int failures;
        /** 当前窗口起点 */
        private long windowStart;
        /** 最后一次失败时间（TTL 判据） */
        private long lastFailureAt;
        /** 锁定截止时间 */
        private long blockedUntil;

        synchronized boolean isBlocked(long now) {
            return blockedUntil > now;
        }

        /** 记一次失败；返回是否刚好达到阈值（达到则锁定一个窗口并清零计数） */
        synchronized boolean fail(long now, long windowMs, int threshold) {
            if (now - windowStart > windowMs) {
                windowStart = now;
                failures = 0;
            }
            failures++;
            lastFailureAt = now;
            if (failures >= threshold) {
                blockedUntil = now + windowMs;
                failures = 0;
                windowStart = now;
                return true;
            }
            return false;
        }

        /** 已过锁定期且长时间无失败 → 可回收 */
        synchronized boolean isStale(long now, long ttlMs) {
            return blockedUntil <= now && now - lastFailureAt > ttlMs;
        }
    }
}
