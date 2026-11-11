package com.bidding.service;

import com.bidding.dto.ChangePasswordRequest;

/**
 * 当前登录用户的写操作（改密在这里；读操作仍在 {@link AuthService} 里）。
 *
 * <p>刻意单独一个接口而不是塞进 {@code AuthService}：{@code AuthService} 负责
 * 注册/登录/签发 token，改密不签发新 token（旧 token 继续有效），
 * 放在 Auth 里会让人误以为会话要刷新。
 */
public interface UserService {

    /**
     * 修改当前登录用户的密码。
     *
     * @param userId  当前登录用户（由 Controller 从 JWT 里取，不从 body 取，避免改别人的密码）
     * @param request 原密码 + 新密码
     */
    void changePassword(Long userId, ChangePasswordRequest request);
}
