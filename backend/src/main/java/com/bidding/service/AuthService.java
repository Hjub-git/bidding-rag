package com.bidding.service;

import com.bidding.dto.LoginRequest;
import com.bidding.dto.RegisterRequest;
import com.bidding.vo.LoginVO;
import com.bidding.vo.UserInfoVO;

public interface AuthService {

    LoginVO register(RegisterRequest request);

    LoginVO login(LoginRequest request);

    UserInfoVO getMe(Long userId);

    void updateMe(Long userId, String email);
}
