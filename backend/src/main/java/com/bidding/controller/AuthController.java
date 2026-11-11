package com.bidding.controller;

import com.bidding.common.BusinessException;
import com.bidding.common.Result;
import com.bidding.common.ResultCode;
import com.bidding.dto.LoginRequest;
import com.bidding.dto.RegisterRequest;
import com.bidding.security.JwtUtil;
import com.bidding.security.TokenBlacklistService;
import com.bidding.service.AuthService;
import com.bidding.vo.LoginVO;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录 / 注册 / 登出。
 *
 * <p>注意：{@code /api/auth/**} 在 {@code SecurityConfig} 里是既有的 permitAll
 * （登录注册本来就该公开）—— 本任务没有改动那条规则、也没有新增任何 permitAll。
 * 因此 {@code /logout} 的「必须登录」由本控制器自己守：拿不到可解析的 token 一律 401，
 * 功能上等价于 authenticated（没有有效 token 就撤不了任何东西）。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    /**
     * 登出：把当前 token 加入服务端黑名单，登出后旧 token 立刻失效。
     *
     * <p>为什么必须有这一步：JWT 24h 有效且无状态，前端只删 localStorage 的话，
     * 旧 token 仍能被任何人用来调接口（被 XSS 偷走、被抓包、共享电脑上的一枚 U 盘快照）。
     *
     * <p>token 从 {@code Authorization: Bearer xxx} 取；{@code exp} 从 token 自身解析，
     * 黑名单条目随 exp 到期自动失效，不会永久占内存。
     *
     * @return 成功统一 {@code Result.ok()}；缺 token / 令牌无效均按现有风格用 BusinessException
     */
    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!StringUtils.hasText(authorization)) {
            // 与「未登录访问受保护接口」保持一致的 401
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录：缺少 Authorization 头");
        }
        if (!authorization.startsWith("Bearer ")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Authorization 头格式错误，应为 Bearer <token>");
        }
        String token = authorization.substring(7).trim();
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Authorization 头格式错误，缺少 token");
        }

        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (Exception e) {
            // 已过期 / 被篡改的 token 无法撤销，也无需撤销（本就无效）
            throw new BusinessException(ResultCode.UNAUTHORIZED, "令牌无效或已过期");
        }

        long expMillis = claims.getExpiration() != null
                ? claims.getExpiration().getTime()
                : System.currentTimeMillis();
        tokenBlacklistService.blacklist(token, expMillis);
        log.info("用户登出，令牌已撤销：username={} exp={}", claims.get("username"), expMillis);
        return Result.ok();
    }
}
