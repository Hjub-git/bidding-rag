package com.bidding.security;

import com.bidding.common.BusinessException;
import com.bidding.common.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 取当前登录用户的小工具。
 * 每次请求里 JwtAuthenticationFilter 已把 LoginUser 放进上下文，这里直接取。
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    private static LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser;
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED);
    }

    public static Long getCurrentUserId() {
        return currentUser().getUserId();
    }

    public static String getCurrentRole() {
        return currentUser().getRole();
    }
}
