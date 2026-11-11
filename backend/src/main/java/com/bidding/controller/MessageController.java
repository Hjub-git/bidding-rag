package com.bidding.controller;

import com.bidding.common.Result;
import com.bidding.dto.MessageRequest;
import com.bidding.dto.ReplyRequest;
import com.bidding.service.MessageService;
import com.bidding.vo.MessageDetailVO;
import com.bidding.vo.MessageVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 留言板接口。列表/详情公开读；发帖/回复/删除需要登录（删除再校验是否本人或管理员）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping("/messages")
    public Result<List<MessageVO>> list() {
        return Result.ok(messageService.listMessages());
    }

    @GetMapping("/messages/{id}")
    public Result<MessageDetailVO> detail(@PathVariable Long id) {
        return Result.ok(messageService.getMessage(id));
    }

    @PostMapping("/messages")
    public Result<Long> create(@Valid @RequestBody MessageRequest request) {
        return Result.ok(messageService.createMessage(request));
    }

    @PutMapping("/messages/{id}/reply")
    public Result<Void> reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest request) {
        messageService.reply(id, request.getContent());
        return Result.ok();
    }

    @DeleteMapping("/messages/{id}")
    public Result<Void> deleteMessage(@PathVariable Long id) {
        messageService.deleteMessage(id);
        return Result.ok();
    }

    @DeleteMapping("/replies/{id}")
    public Result<Void> deleteReply(@PathVariable Long id) {
        messageService.deleteReply(id);
        return Result.ok();
    }
}
