package com.bidding.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageVO {

    private Long id;
    private String title;
    private String content;
    private Integer isPublic;
    private LocalDateTime createdAt;
    private String author;
}
