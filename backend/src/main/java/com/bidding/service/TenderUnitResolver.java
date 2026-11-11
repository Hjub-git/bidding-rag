package com.bidding.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bidding.entity.TenderUnit;
import com.bidding.mapper.TenderUnitMapper;
import com.bidding.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 把「当前登录用户」解析成「所属招标单位 id」。
 *
 * <p>两个 id 不是一回事：{@code users.id} 与 {@code tender_units.id} 是两套主键，
 * {@code tender_projects.tender_unit_id} 指向后者。直接拿 userId 去比对就是稳定越权/误拒。
 *
 * <p>刻意不校验单位审核状态：{@code TenderServiceImpl} 里的同名逻辑带
 * {@code unit.status != active -> 403}，但那是「能不能发布」的闸。提问链路照搬会导致
 * 「招标单位注册后管理员还没审核 -> 连自己项目的问答都进不去」，演示直接卡死。
 * 这里返回 null，由调用方 fail-closed（见 {@code RagController#assertCanAsk}）。
 */
@Service
@RequiredArgsConstructor
public class TenderUnitResolver {

    private final TenderUnitMapper tenderUnitMapper;

    /**
     * @return 当前用户对应的 {@code tender_units.id}；没有档案或角色不符时返回 {@code null}
     */
    public Long currentTenderUnitId() {
        Long userId = SecurityUtil.getCurrentUserId();
        TenderUnit unit = tenderUnitMapper.selectOne(
                new LambdaQueryWrapper<TenderUnit>().eq(TenderUnit::getUserId, userId));
        return unit == null ? null : unit.getId();
    }
}
