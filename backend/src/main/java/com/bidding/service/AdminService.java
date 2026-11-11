package com.bidding.service;

import com.bidding.common.PageResult;
import com.bidding.vo.AdminStatisticsVO;
import com.bidding.vo.AdminUserVO;
import com.bidding.vo.MessageVO;
import com.bidding.vo.TenderVO;

public interface AdminService {

    AdminStatisticsVO statistics();

    PageResult<TenderVO> listTenders(String keyword, String status, long page, long pageSize);

    PageResult<AdminUserVO> listUsers(String role, String keyword, long page, long pageSize);

    void updateUserStatus(Long userId, String status);

    PageResult<MessageVO> listMessages(long page, long pageSize);

    void deleteMessage(Long id);
}
