package com.bidding.controller;

import com.bidding.common.Result;
import com.bidding.dto.ChangePasswordRequest;
import com.bidding.dto.UpdateUserInfoRequest;
import com.bidding.security.SecurityUtil;
import com.bidding.service.AuthService;
import com.bidding.service.UserService;
import com.bidding.vo.UserInfoVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录用户信息接口（任何已登录角色都可调用）。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserService userService;

    @GetMapping("/info")
    public Result<UserInfoVO> getInfo() {
        return Result.ok(authService.getMe(SecurityUtil.getCurrentUserId()));
    }

    @PutMapping("/info")
    public Result<Void> updateInfo(@Valid @RequestBody UpdateUserInfoRequest request) {
        authService.updateMe(SecurityUtil.getCurrentUserId(), request.getEmail());
        return Result.ok();
    }

    /**
     * 修改当前登录用户的密码。
     *
     * <p>复用现有 JWT 拦截：{@code /api/user/**} 已在 {@code anyRequest().authenticated()} 覆盖范围内，
     * 所以这里既不新增 {@code permitAll}，也不加 {@code @PreAuthorize}——免登录就进不来（401），
     * 登录后各角色都能改自己的密码。
     *
     * <p>userId 从 token 取，不读 body：否则谁都能改别人的密码。
     * 成功后不签发新 token（旧 token 继续有效）；失败抛 BusinessException → 全局处理器给 400。
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(SecurityUtil.getCurrentUserId(), request);
        return Result.ok();
    }
}
