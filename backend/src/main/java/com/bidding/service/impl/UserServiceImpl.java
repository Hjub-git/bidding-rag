package com.bidding.service.impl;

import com.bidding.common.BusinessException;
import com.bidding.common.ResultCode;
import com.bidding.dto.ChangePasswordRequest;
import com.bidding.entity.User;
import com.bidding.mapper.UserMapper;
import com.bidding.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * 用户写操作实现（改密）。
 *
 * <p>只碰 {@code users.password} 一列：不新增表、不改结构、不动角色/邮箱；
 * 也不签发新 token（旧 token 仍有效，避免客户端换 token 的会话复杂化）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** 与设计约定一致：新密码长度下限 8、上限 64 字符（BCrypt 只能吃 72 字节，见下） */
    private static final int MIN_LEN = 8;
    private static final int MAX_LEN = 64;
    /** BCrypt 的有效输入上限：超过 72 字节多数实现会直接抛异常 → 500，所以先拦住给 400 */
    private static final int MAX_BCRYPT_BYTES = 72;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // JWT 有效但用户被删：与其它接口一致按 404 处理
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }

        String oldPassword = request.getOldPassword();
        String newPassword = request.getNewPassword();

        // ① 原密码必须匹配（BCrypt），错 → 400「原密码不正确」
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            log.warn("改密拒绝（原密码不正确）userId={}", userId);
            throw new BusinessException(ResultCode.BAD_REQUEST, "原密码不正确");
        }

        // ② 新密码规则：DTO 的 @Size 已拦一道，这里是防注解被绕过的兜底
        if (newPassword == null || newPassword.length() < MIN_LEN) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新密码长度不能少于 " + MIN_LEN + " 位");
        }
        if (newPassword.length() > MAX_LEN
                || newPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新密码过长，最多 " + MAX_LEN + " 位");
        }
        // ③ 新旧密码相同 → 400（要求「新密码必须与旧密码不同」）
        if (newPassword.equals(oldPassword)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "新密码不能与原密码相同");
        }

        // ④ 只更新 password 一列（其它字段为 null，MyBatis-Plus 默认 NOT_NULL 策略不会覆盖）
        User update = new User();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(update);

        log.info("改密成功 userId={} username={}", userId, user.getUsername());
    }
}
