package com.bidding.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度需在 3-20 之间")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度需在 6-20 之间")
    private String password;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "角色不能为空")
    @Pattern(regexp = "manufacturer|tender", message = "角色不合法")
    private String role;

    @NotBlank(message = "公司/单位名称不能为空")
    private String companyName;

    @NotBlank(message = "法人代表不能为空")
    private String legalPerson;

    @NotBlank(message = "联系电话不能为空")
    private String contactPhone;

    @NotBlank(message = "地址不能为空")
    private String address;

    @NotBlank(message = "营业执照不能为空")
    private String businessLicense;

    private String companyProfile;
}
