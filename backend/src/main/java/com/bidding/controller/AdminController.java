package com.bidding.controller;

import com.bidding.common.PageResult;
import com.bidding.common.Result;
import com.bidding.dto.UpdateStatusRequest;
import com.bidding.service.AdminService;
import com.bidding.vo.AdminStatisticsVO;
import com.bidding.vo.AdminUserVO;
import com.bidding.vo.MessageVO;
import com.bidding.vo.TenderVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员接口，全部需要 ADMIN 角色（由 @PreAuthorize 在方法级拦截）。
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/statistics")
    public Result<AdminStatisticsVO> statistics() {
        return Result.ok(adminService.statistics());
    }

    @GetMapping("/users")
    public Result<PageResult<AdminUserVO>> users(@RequestParam(required = false) String role,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(adminService.listUsers(role, keyword, page, pageSize));
    }

    @PutMapping("/users/{id}/status")
    public Result<Void> updateUserStatus(@PathVariable Long id,
                                         @Valid @RequestBody UpdateStatusRequest request) {
        adminService.updateUserStatus(id, request.getStatus());
        return Result.ok();
    }

    @GetMapping("/messages")
    public Result<PageResult<MessageVO>> messages(@RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(adminService.listMessages(page, pageSize));
    }

    @DeleteMapping("/messages/{id}")
    public Result<Void> deleteMessage(@PathVariable Long id) {
        adminService.deleteMessage(id);
        return Result.ok();
    }

    @GetMapping("/tenders")
    public Result<PageResult<TenderVO>> tenders(@RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "10") long pageSize) {
        return Result.ok(adminService.listTenders(keyword, status, page, pageSize));
    }
}
