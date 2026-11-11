package com.bidding.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器：每次请求从 Authorization 头取出 token，解析成功后把用户塞进 SecurityContext。
 * 令牌无效/过期时不在这里抛异常，而是直接放行（保持未认证），由认证入口统一返回 401。
 *
 * <p>解析成功后先查 {@link TokenBlacklistService}，命中（用户已登出）同样保持未认证，
 * 于是受保护接口走认证入口拿 401 —— 与「token 过期」走同一条既有链路，
 * 不新增任何 SecurityConfig 规则，也不改变公开接口的行为。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsServiceImpl userDetailsService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (StringUtils.hasText(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
            // ① 先判撤销（黑名单是 O(1) 的 map 查询，比解析签名便宜，命中就不用再解析了）
            if (tokenBlacklistService.isBlacklisted(token)) {
                // 记录一条 warn：能用来判断「有人拿已登出的旧 token 重放」
                log.warn("拒绝已撤销令牌：uri={} method={}（该 token 已通过 /api/auth/logout 撤销）",
                        request.getRequestURI(), request.getMethod());
                SecurityContextHolder.clearContext();
            } else {
                try {
                    Claims claims = jwtUtil.parseToken(token);
                    String username = claims.get("username", String.class);
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (Exception e) {
                    SecurityContextHolder.clearContext();
                }
            }
        }
        chain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
