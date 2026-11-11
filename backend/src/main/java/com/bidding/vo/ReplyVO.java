package com.bidding.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReplyVO {

    private Long id;
    private String content;
    private LocalDateTime createdAt;
    private String author;
}
