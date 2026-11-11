package com.bidding.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginVO {

    private Long id;
    private String username;
    private String email;
    private String role;
    private String token;
}
