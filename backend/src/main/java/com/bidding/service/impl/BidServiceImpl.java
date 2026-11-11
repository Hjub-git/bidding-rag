package com.bidding.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.bidding.common.BusinessException;
import com.bidding.common.ResultCode;
import com.bidding.common.Roles;
import com.bidding.dto.BidRequest;
import com.bidding.dto.BidUpdateRequest;
import com.bidding.entity.Bid;
import com.bidding.entity.Manufacturer;
import com.bidding.entity.TenderProject;
import com.bidding.entity.TenderUnit;
import com.bidding.mapper.BidMapper;
import com.bidding.mapper.ManufacturerMapper;
import com.bidding.mapper.TenderProjectMapper;
import com.bidding.mapper.TenderUnitMapper;
import com.bidding.security.SecurityUtil;
import com.bidding.service.BidService;
import com.bidding.vo.BidVO;
import com.bidding.vo.MyBidVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BidServiceImpl implements BidService {

    private static final String SUBMITTED = "submitted";
    private static final String ACCEPTED = "accepted";
    private static final String REJECTED = "rejected";

    private final BidMapper bidMapper;
    private final ManufacturerMapper manufacturerMapper;
    private final TenderProjectMapper tenderProjectMapper;
    private final TenderUnitMapper tenderUnitMapper;

    @Override
    public Long createBid(BidRequest request) {
        Long manufacturerId = getCurrentManufacturerId();

        TenderProject project = tenderProjectMapper.selectById(request.getTenderProjectId());
        if (project == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标项目不存在");
        }
        if (!"open".equals(project.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该招标项目当前不可投标");
        }
        // 截止日期校验：open 但已过截止日期的项目不能再投标
        if (project.getDeadline() != null && project.getDeadline().isBefore(LocalDate.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该招标项目已过截止日期，无法投标");
        }
        // 已定标防护（已定标防护）：一个项目只允许定标一次。
        // status 校验挡不住这条——项目被改回 open 后 status 仍是 open，只能查投标表。
        Long accepted = bidMapper.selectCount(new LambdaQueryWrapper<Bid>()
                .eq(Bid::getTenderProjectId, request.getTenderProjectId())
                .eq(Bid::getStatus, ACCEPTED));
        if (accepted != null && accepted > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该项目已定标，不能再次投标");
        }

        // 重复投标防护：同一厂家对同一项目只允许一条投标
        Long exists = bidMapper.selectCount(new LambdaQueryWrapper<Bid>()
                .eq(Bid::getManufacturerId, manufacturerId)
                .eq(Bid::getTenderProjectId, request.getTenderProjectId()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "您已对该项目投过标，请勿重复投标");
        }

        Bid bid = new Bid();
        bid.setTenderProjectId(request.getTenderProjectId());
        bid.setManufacturerId(manufacturerId);
        bid.setBidDate(request.getBidDate() != null ? request.getBidDate() : LocalDate.now());
        bid.setProductionDate(request.getProductionDate());
        bid.setWarrantyPeriod(request.getWarrantyPeriod());
        bid.setTotalAmount(request.getTotalAmount());
        bid.setDepositPaymentDate(request.getDepositPaymentDate());
        bid.setBidDocument(request.getBidDocument());
        bid.setStatus(SUBMITTED);
        try {
            bidMapper.insert(bid);
        } catch (DuplicateKeyException e) {
            // 数据库唯一约束兜底，防并发下重复投标
            throw new BusinessException(ResultCode.BAD_REQUEST, "您已对该项目投过标，请勿重复投标");
        }
        return bid.getId();
    }

    @Override
    public List<BidVO> listBids(Long tenderProjectId) {
        // 越权防护：招标单位只能查看本单位项目的投标（admin 可查看任意）
        TenderProject project = tenderProjectMapper.selectById(tenderProjectId);
        if (project == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标项目不存在");
        }
        assertTenderOwnership(project);
        return bidMapper.selectBidVOList(tenderProjectId);
    }

    @Override
    public List<MyBidVO> listMyBids() {
        return bidMapper.selectMyBidVOList(getCurrentManufacturerId());
    }

    @Override
    public void updateBid(Long id, BidUpdateRequest request) {
        Bid bid = getOwnedBid(id);
        if (!SUBMITTED.equals(bid.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该投标已处理，无法修改");
        }
        // 截止校验（截止校验）：项目必须仍开放且未过截止日期。
        // 投标表自己的 status 是 submitted 不够——项目截止/定标后再改价等于事后改标。
        TenderProject project = tenderProjectMapper.selectById(bid.getTenderProjectId());
        if (project == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标项目不存在");
        }
        if (!"open".equals(project.getStatus())
                || (project.getDeadline() != null && project.getDeadline().isBefore(LocalDate.now()))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "项目已截止，不能修改投标");
        }
        Bid update = new Bid();
        update.setId(id);
        update.setProductionDate(request.getProductionDate());
        update.setWarrantyPeriod(request.getWarrantyPeriod());
        update.setTotalAmount(request.getTotalAmount());
        update.setDepositPaymentDate(request.getDepositPaymentDate());
        update.setBidDocument(request.getBidDocument());
        bidMapper.updateById(update);
    }

    @Override
    public void withdrawBid(Long id) {
        Bid bid = getOwnedBid(id);
        if (!SUBMITTED.equals(bid.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该投标已处理，无法撤回");
        }
        // 逻辑删除（@TableLogic 转 UPDATE deleted=1）
        bidMapper.deleteById(bid.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acceptBid(Long id) {
        decideBid(id, ACCEPTED);
    }

    @Override
    public void rejectBid(Long id) {
        decideBid(id, REJECTED);
    }

    /** 处理投标（接受/拒绝）：招标单位只能处理本单位项目的投标 */
    private void decideBid(Long id, String status) {
        Bid bid = bidMapper.selectById(id);
        if (bid == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "投标不存在");
        }
        if (!SUBMITTED.equals(bid.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该投标已处理");
        }

        TenderProject project = tenderProjectMapper.selectById(bid.getTenderProjectId());
        if (project == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标项目不存在");
        }
        assertTenderOwnership(project);

        if (ACCEPTED.equals(status)) {
            // 定标互斥（条件更新）：先条件关闭项目，把「项目行」当作本项目的唯一写入锁。
            // 同一项目并发接受时只有一个事务能更新到这一行，另一个拿到 0 行 → 400，
            // 因此不会出现「双接受」，也不会有先锁 A 再锁 B 的死锁（锁顺序固定：先项目后投标）。
            TenderProject close = new TenderProject();
            close.setStatus("closed");
            int closed = tenderProjectMapper.update(close, new LambdaUpdateWrapper<TenderProject>()
                    .eq(TenderProject::getId, project.getId())
                    .eq(TenderProject::getStatus, "open"));
            if (closed == 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "该项目已定标，不能重复定标");
            }
        }

        // 条件更新（条件更新）：只有仍是 submitted 的投标能被处理到，影响行数 0 → 说明已被别人改过
        Bid decided = new Bid();
        decided.setStatus(status);
        int affected = bidMapper.update(decided, new LambdaUpdateWrapper<Bid>()
                .eq(Bid::getId, id)
                .eq(Bid::getStatus, SUBMITTED));
        if (affected == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该投标已处理或状态已变更");
        }

        // 定标互斥 + 封标：接受一个投标后，同项目其余未处理投标自动拒绝
        if (ACCEPTED.equals(status)) {
            Bid rejectOthers = new Bid();
            rejectOthers.setStatus(REJECTED);
            bidMapper.update(rejectOthers, new LambdaUpdateWrapper<Bid>()
                    .eq(Bid::getTenderProjectId, bid.getTenderProjectId())
                    .eq(Bid::getStatus, SUBMITTED)
                    .ne(Bid::getId, id));
        }
    }

    /** 越权防护：招标单位只能操作本单位项目的资源（admin 可操作任意） */
    private void assertTenderOwnership(TenderProject project) {
        // 大小写不敏感（大小写不敏感）：库里 role 理论上全小写，但用 equals 时一旦出现 "TENDER"
        // 就会整体跳过归属校验 → 跨租户越权，这类判定必须 fail-closed。
        if (Roles.TENDER.equalsIgnoreCase(SecurityUtil.getCurrentRole())
                && !getCurrentTenderUnitId().equals(project.getTenderUnitId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能操作本单位招标项目");
        }
    }

    /** 校验投标属于当前厂家，并返回 */
    private Bid getOwnedBid(Long id) {
        Bid bid = bidMapper.selectById(id);
        if (bid == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "投标不存在");
        }
        if (!getCurrentManufacturerId().equals(bid.getManufacturerId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能操作自己的投标");
        }
        return bid;
    }

    private Long getCurrentManufacturerId() {
        Long userId = SecurityUtil.getCurrentUserId();
        Manufacturer m = manufacturerMapper.selectOne(
                new LambdaQueryWrapper<Manufacturer>().eq(Manufacturer::getUserId, userId));
        if (m == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "厂家信息不存在");
        }
        // 审核状态强制生效：pending/rejected 的厂家不能投标
        if (!"active".equals(m.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "厂家账号尚未通过审核，无法投标");
        }
        return m.getId();
    }

    private Long getCurrentTenderUnitId() {
        Long userId = SecurityUtil.getCurrentUserId();
        TenderUnit t = tenderUnitMapper.selectOne(
                new LambdaQueryWrapper<TenderUnit>().eq(TenderUnit::getUserId, userId));
        if (t == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "招标单位信息不存在");
        }
        if (!"active".equals(t.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "招标单位账号尚未通过审核");
        }
        return t.getId();
    }
}
