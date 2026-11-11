package com.bidding.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bidding.common.BusinessException;
import com.bidding.common.PageResult;
import com.bidding.common.ResultCode;
import com.bidding.common.Roles;
import com.bidding.entity.Bid;
import com.bidding.entity.Manufacturer;
import com.bidding.entity.Message;
import com.bidding.entity.TenderProject;
import com.bidding.entity.TenderUnit;
import com.bidding.entity.User;
import com.bidding.mapper.BidMapper;
import com.bidding.mapper.ManufacturerMapper;
import com.bidding.mapper.MessageMapper;
import com.bidding.mapper.TenderProjectMapper;
import com.bidding.mapper.TenderUnitMapper;
import com.bidding.mapper.UserMapper;
import com.bidding.service.AdminService;
import com.bidding.vo.AdminStatisticsVO;
import com.bidding.vo.AdminUserVO;
import com.bidding.vo.MessageVO;
import com.bidding.vo.TenderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final UserMapper userMapper;
    private final ManufacturerMapper manufacturerMapper;
    private final TenderUnitMapper tenderUnitMapper;
    private final TenderProjectMapper tenderProjectMapper;
    private final BidMapper bidMapper;
    private final MessageMapper messageMapper;

    @Override
    public AdminStatisticsVO statistics() {
        return new AdminStatisticsVO(
                count(userMapper),
                count(manufacturerMapper),
                count(tenderUnitMapper),
                count(tenderProjectMapper),
                count(bidMapper),
                count(messageMapper));
    }

    @Override
    public PageResult<TenderVO> listTenders(String keyword, String status, long page, long pageSize) {
        IPage<TenderVO> result = tenderProjectMapper.selectTenderPage(
                new Page<>(normalizePage(page), normalizePageSize(pageSize)),
                normalize(status), normalize(keyword));
        return new PageResult<>(result.getRecords(), result.getTotal());
    }

    @Override
    public PageResult<AdminUserVO> listUsers(String role, String keyword, long page, long pageSize) {
        IPage<AdminUserVO> result = userMapper.selectAdminUserPage(
                new Page<>(normalizePage(page), normalizePageSize(pageSize)),
                normalize(role), normalize(keyword));
        return new PageResult<>(result.getRecords(), result.getTotal());
    }

    @Override
    public void updateUserStatus(Long userId, String status) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        // 大小写不敏感（大小写不敏感）：库里 role 理论上全小写，但 equals 一旦漏匹配，
        // 下面的分支全不命中 → 审核动作被静默吞掉（不报错也不生效）。
        if (Roles.ADMIN.equalsIgnoreCase(user.getRole())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "管理员无需审核");
        }
        if (Roles.MANUFACTURER.equalsIgnoreCase(user.getRole())) {
            updateManufacturerStatus(userId, status);
        } else if (Roles.TENDER.equalsIgnoreCase(user.getRole())) {
            updateTenderUnitStatus(userId, status);
        }
    }

    @Override
    public PageResult<MessageVO> listMessages(long page, long pageSize) {
        IPage<MessageVO> result = messageMapper.selectAdminMessagePage(
                new Page<>(normalizePage(page), normalizePageSize(pageSize)));
        return new PageResult<>(result.getRecords(), result.getTotal());
    }

    @Override
    public void deleteMessage(Long id) {
        messageMapper.deleteById(id);
    }

    private void updateManufacturerStatus(Long userId, String status) {
        Manufacturer m = manufacturerMapper.selectOne(
                new LambdaQueryWrapper<Manufacturer>().eq(Manufacturer::getUserId, userId));
        if (m == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "厂家档案不存在");
        }
        Manufacturer update = new Manufacturer();
        update.setId(m.getId());
        update.setStatus(status);
        manufacturerMapper.updateById(update);
    }

    private void updateTenderUnitStatus(Long userId, String status) {
        TenderUnit t = tenderUnitMapper.selectOne(
                new LambdaQueryWrapper<TenderUnit>().eq(TenderUnit::getUserId, userId));
        if (t == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标单位档案不存在");
        }
        TenderUnit update = new TenderUnit();
        update.setId(t.getId());
        update.setStatus(status);
        tenderUnitMapper.updateById(update);
    }

    private long count(com.baomidou.mybatisplus.core.mapper.BaseMapper<?> mapper) {
        Long n = mapper.selectCount(null);
        return n == null ? 0 : n;
    }

    private String normalize(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    private long normalizePage(long page) {
        return Math.max(page, 1);
    }

    private long normalizePageSize(long pageSize) {
        return Math.min(Math.max(pageSize, 1), 100);
    }
}
