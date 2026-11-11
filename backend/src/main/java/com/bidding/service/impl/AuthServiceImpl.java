package com.bidding.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bidding.common.BusinessException;
import com.bidding.common.ResultCode;
import com.bidding.common.Roles;
import com.bidding.dto.LoginRequest;
import com.bidding.dto.RegisterRequest;
import com.bidding.entity.Manufacturer;
import com.bidding.entity.TenderUnit;
import com.bidding.entity.User;
import com.bidding.mapper.ManufacturerMapper;
import com.bidding.mapper.TenderUnitMapper;
import com.bidding.mapper.UserMapper;
import com.bidding.security.JwtUtil;
import com.bidding.security.LoginAttemptService;
import com.bidding.service.AuthService;
import com.bidding.vo.LoginVO;
import com.bidding.vo.UserInfoVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final ManufacturerMapper manufacturerMapper;
    private final TenderUnitMapper tenderUnitMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginAttemptService loginAttemptService;

    /**
     * 注册：创建用户 + 按角色创建厂家/招标单位档案。
     * @Transactional 保证两步要么都成功要么都回滚（依赖数据库事务的原子性）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(RegisterRequest request) {
        // 角色白名单兜底（DTO 已用 @Pattern 校验，这里再做一次防御式校验，防止未来绕过注解）。
        // 大小写不敏感：判定用 equalsIgnoreCase，但先把入参归一成小写再落库——
        // 只放宽「比较」不放宽「存储」，避免 users.role 里出现 "MANUFACTURER" 这种脏值。
        String role = request.getRole() == null ? null : request.getRole().trim().toLowerCase(Locale.ROOT);
        if (!Roles.MANUFACTURER.equalsIgnoreCase(role) && !Roles.TENDER.equalsIgnoreCase(role)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "角色不合法");
        }
        // 唯一性校验（数据库唯一约束兜底并发场景）
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername())
                .or()
                .eq(User::getEmail, request.getEmail()));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名或邮箱已存在");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        // 密码必须加密存储，绝不存明文
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setRole(role);
        userMapper.insert(user);

        if (Roles.MANUFACTURER.equalsIgnoreCase(role)) {
            Manufacturer m = new Manufacturer();
            m.setUserId(user.getId());
            m.setCompanyName(request.getCompanyName());
            m.setLegalPerson(request.getLegalPerson());
            m.setContactPhone(request.getContactPhone());
            m.setAddress(request.getAddress());
            m.setBusinessLicense(request.getBusinessLicense());
            m.setCompanyProfile(request.getCompanyProfile());
            m.setStatus("pending");
            manufacturerMapper.insert(m);
        } else if (Roles.TENDER.equalsIgnoreCase(role)) {
            TenderUnit t = new TenderUnit();
            t.setUserId(user.getId());
            t.setCompanyName(request.getCompanyName());
            t.setLegalPerson(request.getLegalPerson());
            t.setContactPhone(request.getContactPhone());
            t.setAddress(request.getAddress());
            t.setBusinessLicense(request.getBusinessLicense());
            t.setCompanyProfile(request.getCompanyProfile());
            t.setStatus("pending");
            tenderUnitMapper.insert(t);
        }

        return buildLoginVO(user);
    }

    @Override
    public LoginVO login(LoginRequest request) {
        String key = request.getUsername() + "|" + clientIp();
        // 防爆破：锁定中直接拒绝
        loginAttemptService.checkBlocked(key);

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        // 统一提示“用户名或密码错误”，不暴露到底哪个错（防用户名枚举）
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptService.onFailure(key);
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        loginAttemptService.onSuccess(key);
        return buildLoginVO(user);
    }

    @Override
    public UserInfoVO getMe(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        UserInfoVO vo = new UserInfoVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setEmail(user.getEmail());
        vo.setRole(user.getRole());
        return vo;
    }

    @Override
    public void updateMe(Long userId, String email) {
        // 邮箱唯一性校验，避免触发数据库唯一约束后返回 500
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, email)
                .ne(User::getId, userId));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "邮箱已被使用");
        }
        User user = new User();
        user.setId(userId);
        user.setEmail(email);
        userMapper.updateById(user);
    }

    /** 获取当前请求的客户端 IP，用于登录防爆破的 key 维度 */
    private String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRemoteAddr();
        }
        return "unknown";
    }

    private LoginVO buildLoginVO(User user) {
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());
        return new LoginVO(user.getId(), user.getUsername(), user.getEmail(), user.getRole(), token);
    }
}
