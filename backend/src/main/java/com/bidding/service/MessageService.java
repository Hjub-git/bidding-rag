package com.bidding.service;

import com.bidding.dto.MessageRequest;
import com.bidding.vo.MessageDetailVO;
import com.bidding.vo.MessageVO;

import java.util.List;

public interface MessageService {

    List<MessageVO> listMessages();

    MessageDetailVO getMessage(Long id);

    Long createMessage(MessageRequest request);

    void reply(Long messageId, String content);

    void deleteMessage(Long id);

    void deleteReply(Long id);
}
