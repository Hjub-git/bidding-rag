package com.bidding.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求体。
 *
 * <p>字段名用 {@code @JsonProperty} 显式钉成 camelCase：项目全局配了
 * {@code spring.jackson.property-naming-strategy: SNAKE_CASE}，不钉的话接口契约会变成
 * {@code old_password}，与设计约定约定的 {@code {"oldPassword": "...", "newPassword": "..."}} 不一致。
 * 同时用 {@code @JsonAlias} 兼容 snake_case 写法，两种 body 都能收（不新增第二套接口）。
 *
 * <p>长度下限 {@code ≥ 8} 在这里用 {@code @Size} 拦一道，Service 里再做一次防御式校验
 * （防止未来绕过注解直接调 Service）。
 */
@Data
public class ChangePasswordRequest {

    @JsonProperty("oldPassword")
    @JsonAlias({"old_password"})
    @NotBlank(message = "原密码不能为空")
    private String oldPassword;

    @JsonProperty("newPassword")
    @JsonAlias({"new_password"})
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "新密码长度需在 8~64 位之间")
    private String newPassword;
}
