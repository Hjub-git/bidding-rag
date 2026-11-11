package com.bidding.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bidding.common.BusinessException;
import com.bidding.common.PageResult;
import com.bidding.common.ResultCode;
import com.bidding.common.Roles;
import com.bidding.dto.TenderRequest;
import com.bidding.dto.TenderUpdateRequest;
import com.bidding.entity.TenderProject;
import com.bidding.entity.TenderUnit;
import com.bidding.mapper.TenderProjectMapper;
import com.bidding.mapper.TenderUnitMapper;
import com.bidding.security.SecurityUtil;
import com.bidding.service.TenderService;
import com.bidding.vo.TenderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TenderServiceImpl implements TenderService {

    /** 状态白名单（状态机）：除此之外一律 400，避免任意字符串写进 status 列 */
    private static final Set<String> ALLOWED_STATUS = Set.of("open", "closed", "cancelled");

    private static final String OPEN = "open";
    private static final String CLOSED = "closed";
    private static final String CANCELLED = "cancelled";

    private final TenderProjectMapper tenderProjectMapper;
    private final TenderUnitMapper tenderUnitMapper;

    @Override
    public PageResult<TenderVO> listTenders(String keyword, String status, long page, long pageSize) {
        IPage<TenderVO> result = tenderProjectMapper.selectTenderPage(
                new Page<>(normalizePage(page), normalizePageSize(pageSize)),
                normalize(status), normalize(keyword));
        return new PageResult<>(result.getRecords(), result.getTotal());
    }

    @Override
    public TenderVO getTender(Long id) {
        TenderVO vo = tenderProjectMapper.selectTenderVOById(id);
        if (vo == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标项目不存在");
        }
        return vo;
    }

    @Override
    public TenderVO createTender(TenderRequest request) {
        Long unitId = getCurrentTenderUnitId();
        TenderProject tp = new TenderProject();
        tp.setProjectNumber(request.getProjectNumber());
        tp.setTenderUnitId(unitId);
        tp.setEquipmentName(request.getEquipmentName());
        tp.setModel(request.getModel());
        tp.setPackagingRequirements(request.getPackagingRequirements());
        tp.setQuantity(request.getQuantity());
        tp.setDeadline(request.getDeadline());
        tp.setDeposit(request.getDeposit());
        tp.setDescription(request.getDescription());
        tp.setStatus("open");
        try {
            tenderProjectMapper.insert(tp);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "项目编号已存在，请更换唯一编号");
        }
        return tenderProjectMapper.selectTenderVOById(tp.getId());
    }

    @Override
    public void updateTender(Long id, TenderUpdateRequest request) {
        TenderProject tp = tenderProjectMapper.selectById(id);
        if (tp == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标项目不存在");
        }
        // 越权防护：招标单位只能改本单位项目（admin 可改任意）
        // 大小写不敏感（大小写不敏感）：用 equals 时库里一旦出现 "TENDER" 就会整体跳过归属校验
        if (Roles.TENDER.equalsIgnoreCase(SecurityUtil.getCurrentRole())
                && !getCurrentTenderUnitId().equals(tp.getTenderUnitId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能修改本单位发布的招标项目");
        }

        String currentStatus = lower(tp.getStatus());
        String targetStatus = lower(request.getStatus());
        assertStatusTransitionAllowed(currentStatus, targetStatus);
        assertDeadlineChangeAllowed(currentStatus, tp.getDeadline(), request.getDeadline());

        // 只更新传入的非空字段（MyBatis-Plus updateById 默认忽略 null，等价于 SQL 的 COALESCE）
        TenderProject update = new TenderProject();
        update.setId(id);
        update.setProjectNumber(normalize(request.getProjectNumber()));
        update.setEquipmentName(normalize(request.getEquipmentName()));
        update.setModel(normalize(request.getModel()));
        update.setPackagingRequirements(normalize(request.getPackagingRequirements()));
        update.setQuantity(request.getQuantity());
        update.setDeadline(request.getDeadline());
        update.setDeposit(request.getDeposit());
        update.setDescription(normalize(request.getDescription()));
        update.setStatus(targetStatus);
        tenderProjectMapper.updateById(update);
    }

    /**
     * 状态流转校验：白名单 open|closed|cancelled，其余一律 400。
     *
     * <p>closed 不能回 open，任何人都不允许（400「已定标，不能重新开放」）。定标（accept）
     * 在同一事务里把项目置 closed，所以「已有 accepted 投标 ⇒ 项目是 closed」，这条统一
     * 禁止已经覆盖「已定标项目不可重开」。
     *
     * <p>cancelled -> open 只有 admin 可以（例外通道，用于误取消后恢复）；closed -> cancelled
     * 也只有 admin 可以，招标单位不能动已定标项目。
     *
     * <p>只在「状态真的变了」时判定：前端编辑框每次都会原样回传 status，不变就不该拦。
     */
    private void assertStatusTransitionAllowed(String current, String target) {
        if (target != null && !ALLOWED_STATUS.contains(target)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态只能是 open / closed / cancelled");
        }
        if (target == null || target.equals(current)) {
            return;
        }
        boolean admin = Roles.ADMIN.equalsIgnoreCase(SecurityUtil.getCurrentRole());
        if (OPEN.equals(target) && CLOSED.equals(current)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该项目已定标，不能重新开放");
        }
        if (!admin && CLOSED.equals(current)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "已定标项目只有管理员可以变更状态");
        }
        if (!admin && OPEN.equals(target) && CANCELLED.equals(current)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只有管理员可以重新开放已取消的项目");
        }
    }

    /**
     * 截止日期校验：已 closed（已定标/关闭）的项目不许改 deadline，新 deadline 也不能早于今天。
     *
     * <p>同样只在「deadline 真的变了」时判定——前端编辑框总会原样回传 deadline，
     * 否则连改个型号都会因为库里那条历史日期被误杀。
     */
    private void assertDeadlineChangeAllowed(String currentStatus, LocalDate oldDeadline, LocalDate newDeadline) {
        if (newDeadline == null || newDeadline.equals(oldDeadline)) {
            return;
        }
        if (CLOSED.equals(currentStatus)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "项目已关闭（已定标），不能修改截止日期");
        }
        if (newDeadline.isBefore(LocalDate.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "截止日期不能早于今天");
        }
    }

    @Override
    public void deleteTender(Long id) {
        TenderProject tp = tenderProjectMapper.selectById(id);
        if (tp == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标项目不存在");
        }
        if (Roles.TENDER.equalsIgnoreCase(SecurityUtil.getCurrentRole())
                && !getCurrentTenderUnitId().equals(tp.getTenderUnitId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能删除本单位发布的招标项目");
        }
        // 逻辑删除：@TableLogic 会把 DELETE 转成 UPDATE deleted=1
        tenderProjectMapper.deleteById(id);
    }

    @Override
    public List<TenderVO> listMyTenders() {
        return tenderProjectMapper.selectMyTenderVOList(getCurrentTenderUnitId());
    }

    private Long getCurrentTenderUnitId() {
        Long userId = SecurityUtil.getCurrentUserId();
        TenderUnit unit = tenderUnitMapper.selectOne(
                new LambdaQueryWrapper<TenderUnit>().eq(TenderUnit::getUserId, userId));
        if (unit == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "招标单位信息不存在");
        }
        // 审核状态强制生效：pending/rejected 的招标单位不能发布/管理招标
        if (!"active".equals(unit.getStatus())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "招标单位账号尚未通过审核");
        }
        return unit.getId();
    }

    /** 空串/空白串归一为 null，避免把空值写进数据库 */
    private String normalize(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    /** 状态归一：去空白 + 转小写，便于白名单与流转判定（库里理论上都已是小写） */
    private String lower(String s) {
        return StringUtils.hasText(s) ? s.trim().toLowerCase(Locale.ROOT) : null;
    }

    /** 页码归一：最小为 1 */
    private long normalizePage(long page) {
        return Math.max(page, 1);
    }

    /** 每页数量归一：限制在 1~100，防止 pageSize 过大拖垮接口 */
    private long normalizePageSize(long pageSize) {
        return Math.min(Math.max(pageSize, 1), 100);
    }
}
