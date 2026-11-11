package com.bidding.service.impl;

import com.bidding.common.BusinessException;
import com.bidding.common.ResultCode;
import com.bidding.common.Roles;
import com.bidding.dto.MessageRequest;
import com.bidding.entity.Message;
import com.bidding.entity.Reply;
import com.bidding.mapper.MessageMapper;
import com.bidding.mapper.ReplyMapper;
import com.bidding.security.SecurityUtil;
import com.bidding.service.MessageService;
import com.bidding.vo.MessageDetailVO;
import com.bidding.vo.MessageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private final MessageMapper messageMapper;
    private final ReplyMapper replyMapper;

    @Override
    public List<MessageVO> listMessages() {
        return messageMapper.selectPublicMessages();
    }

    @Override
    public MessageDetailVO getMessage(Long id) {
        MessageVO message = messageMapper.selectPublicMessageById(id);
        if (message == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "留言不存在");
        }
        MessageDetailVO detail = new MessageDetailVO();
        detail.setMessage(message);
        detail.setReplies(replyMapper.selectReplyVOByMessageId(id));
        return detail;
    }

    @Override
    public Long createMessage(MessageRequest request) {
        Message message = new Message();
        message.setUserId(SecurityUtil.getCurrentUserId());
        message.setTitle(request.getTitle());
        message.setContent(request.getContent());
        message.setTenderProjectId(request.getTenderProjectId());
        message.setIsPublic(request.getIsPublic() != null ? request.getIsPublic() : 1);
        messageMapper.insert(message);
        return message.getId();
    }

    @Override
    public void reply(Long messageId, String content) {
        // 目标留言必须存在（公开），否则 404
        if (messageMapper.selectPublicMessageById(messageId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "留言不存在");
        }
        Reply reply = new Reply();
        reply.setMessageId(messageId);
        reply.setUserId(SecurityUtil.getCurrentUserId());
        reply.setContent(content);
        replyMapper.insert(reply);
    }

    @Override
    public void deleteMessage(Long id) {
        Message message = messageMapper.selectById(id);
        if (message == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "留言不存在");
        }
        // 只有作者本人或管理员能删
        if (!isOwnerOrAdmin(message.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能删除自己的留言");
        }
        messageMapper.deleteById(id);
    }

    @Override
    public void deleteReply(Long id) {
        Reply reply = replyMapper.selectById(id);
        if (reply == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "回复不存在");
        }
        if (!isOwnerOrAdmin(reply.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能删除自己的回复");
        }
        replyMapper.deleteById(id);
    }

    private boolean isOwnerOrAdmin(Long ownerUserId) {
        // 大小写不敏感（大小写不敏感）：role 判定一律 fail-closed
        if (Roles.ADMIN.equalsIgnoreCase(SecurityUtil.getCurrentRole())) {
            return true;
        }
        return SecurityUtil.getCurrentUserId().equals(ownerUserId);
    }
}
