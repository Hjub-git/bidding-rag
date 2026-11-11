package com.bidding.controller;

import com.bidding.common.Result;
import com.bidding.entity.TenderDocument;
import com.bidding.service.RagPermissionService;
import com.bidding.service.RagUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 招标文件上传端点。
 *
 * <p>写权限单独一道闸：这里调 {@link RagPermissionService#assertCanUpload}，不是
 * {@code assertCanAsk}。上传会落盘并灌向量库，只有 admin 与该 tender 归属的招标单位能做；
 * manufacturer 是只读角色，问他能问的项目也必须 403（早期修过的一个 P0 安全洞）。
 *
 * <p>问答（{@code /api/rag/chat}）仍走 {@code assertCanAsk}，两条链路互不影响。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagUploadController {

    private final RagPermissionService ragPermissionService;
    private final RagUploadService ragUploadService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<TenderDocument> upload(@RequestParam("tender_id") Long tenderId,
                                         @RequestParam("file") MultipartFile file) {
        // 先判写权限，再碰任何落盘 / Python 入库资源
        ragPermissionService.assertCanUpload(tenderId);
        log.info("上传请求 tender_id={} file={} size={}", tenderId,
                file.getOriginalFilename(), file.getSize());
        return Result.ok(ragUploadService.upload(tenderId, file));
    }
}
