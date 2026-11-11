package com.bidding.controller;

import com.bidding.common.Result;
import com.bidding.dto.BidRequest;
import com.bidding.dto.BidUpdateRequest;
import com.bidding.service.BidService;
import com.bidding.vo.BidVO;
import com.bidding.vo.MyBidVO;
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
 * 投标接口。
 * 厂家：投标 / 我的投标 / 修改 / 撤回；
 * 招标单位/管理员：查看某项目投标、接受/拒绝。
 */
@RestController
@RequestMapping("/api/bids")
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;

    /** 某项目的投标列表（招标单位/管理员视角） */
    @GetMapping
    @PreAuthorize("hasAnyRole('TENDER','ADMIN')")
    public Result<List<BidVO>> list(@RequestParam Long tenderProjectId) {
        return Result.ok(bidService.listBids(tenderProjectId));
    }

    /** 我的投标（厂家视角） */
    @GetMapping("/mine")
    @PreAuthorize("hasRole('MANUFACTURER')")
    public Result<List<MyBidVO>> mine() {
        return Result.ok(bidService.listMyBids());
    }

    @PostMapping
    @PreAuthorize("hasRole('MANUFACTURER')")
    public Result<Long> create(@Valid @RequestBody BidRequest request) {
        return Result.ok(bidService.createBid(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('MANUFACTURER')")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody BidUpdateRequest request) {
        bidService.updateBid(id, request);
        return Result.ok();
    }

    /** 撤回投标 */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MANUFACTURER')")
    public Result<Void> withdraw(@PathVariable Long id) {
        bidService.withdrawBid(id);
        return Result.ok();
    }

    @PutMapping("/{id}/accept")
    @PreAuthorize("hasAnyRole('TENDER','ADMIN')")
    public Result<Void> accept(@PathVariable Long id) {
        bidService.acceptBid(id);
        return Result.ok();
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('TENDER','ADMIN')")
    public Result<Void> reject(@PathVariable Long id) {
        bidService.rejectBid(id);
        return Result.ok();
    }
}
