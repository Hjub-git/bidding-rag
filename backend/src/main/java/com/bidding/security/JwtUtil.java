package com.bidding.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具类：生成令牌、解析令牌。
 * JWT 是无状态的 —— 服务端不存会话，只靠签名验证令牌真伪，适合分布式横向扩展（无状态设计）。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expiration;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration}") long expiration) {
        // 安全底线：拒绝空 / 过短 / 仓库默认密钥，缺失即 fail-fast。
        // 背景：仓库曾内置一个公开默认 secret，任何人都能拿它自签 admin token（实测可接管全站）。
        final String LEGACY_DEFAULT = "bidding-system-jwt-secret-key-please-change-in-prod-0123456789";
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET 未配置：请通过环境变量 JWT_SECRET 注入至少 32 字节随机密钥");
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException("JWT_SECRET 太短（" + raw.length + " 字节）：HS256 要求至少 32 字节");
        }
        if (LEGACY_DEFAULT.equals(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET 仍是仓库默认值，任何人都能伪造 token：请注入随机密钥（start-all.ps1 会自动生成）");
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.expiration = expiration;
    }

    public String generateToken(Long userId, String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expiration * 1000);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                // 加 jti（唯一标识）。原因：iat/exp 只精确到「秒」，同一用户在同一秒内登录两次
                // 会生成逐字节相同的 token；有了撤销黑名单后，这会导致「登出后立刻重新登录」
                // 拿到的新 token 与被撤销的那枚相同 → 新 token 也立刻 401（实测复现）。
                // jti 只加随机性，不改 iat/exp，因此 24h 有效期与既有的令牌格式校验完全不变。
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** 解析并校验令牌，校验失败（过期/篡改）会抛异常 */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
