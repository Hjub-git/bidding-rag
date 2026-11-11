package com.bidding.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MessageRequest {

    @NotBlank(message = "标题不能为空")
    private String title;

    @NotBlank(message = "内容不能为空")
    private String content;

    /** 关联的招标项目，可选 */
    private Long tenderProjectId;

    /** 是否公开：1=公开，0=私密，默认公开 */
    private Integer isPublic;
}
