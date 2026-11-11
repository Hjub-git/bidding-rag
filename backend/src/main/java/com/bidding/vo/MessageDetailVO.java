package com.bidding.vo;

import lombok.Data;

import java.util.List;

@Data
public class MessageDetailVO {

    private MessageVO message;
    private List<ReplyVO> replies;
}
