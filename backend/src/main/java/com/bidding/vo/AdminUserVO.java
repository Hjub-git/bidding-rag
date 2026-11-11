package com.bidding.vo;

import lombok.Data;

@Data
public class AdminUserVO {

    private Long id;
    private String username;
    private String email;
    private String role;
    private String status;
}
