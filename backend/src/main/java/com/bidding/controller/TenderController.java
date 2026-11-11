package com.bidding.controller;

import com.bidding.common.PageResult;
import com.bidding.common.Result;
import com.bidding.dto.TenderRequest;
import com.bidding.dto.TenderUpdateRequest;
import com.bidding.service.TenderService;
import com.bidding.vo.TenderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 招标项目接口。
 * 列表/详情对所有人公开（SecurityConfig 已放行 GET /api/tenders/**）；
 * 发布仅限招标单位（需要关联到本单位档案）；
 * 修改/删除：招标单位可操作本单位项目，管理员可操作任意项目。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TenderController {

    private final TenderService tenderService;

    @GetMapping("/tenders")
    public Result<PageResult<TenderVO>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(tenderService.listTenders(keyword, status, page, pageSize));
    }

    @GetMapping("/tenders/{id}")
    public Result<TenderVO> detail(@PathVariable Long id) {
        return Result.ok(tenderService.getTender(id));
    }

    /** 当前招标单位的项目（放在 /tenders/{id} 之前单独路由，避免被 id 误匹配） */
    @GetMapping("/my-tenders")
    @PreAuthorize("hasRole('TENDER')")
    public Result<List<TenderVO>> myTenders() {
        return Result.ok(tenderService.listMyTenders());
    }

    @PostMapping("/tenders")
    @PreAuthorize("hasRole('TENDER')")
    public Result<TenderVO> create(@Valid @RequestBody TenderRequest request) {
        return Result.ok(tenderService.createTender(request));
    }

    @PutMapping("/tenders/{id}")
    @PreAuthorize("hasAnyRole('TENDER','ADMIN')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody TenderUpdateRequest request) {
        tenderService.updateTender(id, request);
        return Result.ok();
    }

    @DeleteMapping("/tenders/{id}")
    @PreAuthorize("hasAnyRole('TENDER','ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        tenderService.deleteTender(id);
        return Result.ok();
    }
}
