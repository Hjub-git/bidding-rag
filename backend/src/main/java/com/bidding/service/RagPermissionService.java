package com.bidding.service;

import com.bidding.common.BusinessException;
import com.bidding.common.ResultCode;
import com.bidding.common.Roles;
import com.bidding.entity.TenderProject;
import com.bidding.mapper.TenderProjectMapper;
import com.bidding.security.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * RAG 权限判定的唯一入口，但「能不能问」与「能不能传」是两道独立的闸。
 *
 * <p>{@link #assertCanAsk(Long)}：问答（{@code RagController}）用。厂家对开放项目是只读的，
 * 所以可以问。
 *
 * <p>{@link #assertCanUpload(Long)}：上传（{@code RagUploadController}）用。写权限只属于
 * 招标方——admin 与该 tender 归属的招标单位；厂家一律 403。两者刻意不共用：
 * 复用提问判定会让任一投标方向任意开放项目上传文件并灌进向量库（早期的一个 P0 安全洞）。
 *
 * <p>提问规则：
 * <table>
 *   <tr><td>admin</td><td>放行</td></tr>
 *   <tr><td>tender</td><td>本单位项目才放行</td></tr>
 *   <tr><td>manufacturer</td><td>cancelled → 403；保密期内 → 403；其余放行</td></tr>
 *   <tr><td>其他/未知</td><td>fail-closed 403</td></tr>
 * </table>
 *
 * <p>上传规则：
 * <table>
 *   <tr><td>admin</td><td>放行</td></tr>
 *   <tr><td>tender</td><td>仅本单位名下项目放行，其余 403</td></tr>
 *   <tr><td>manufacturer</td><td>一律 403（与项目状态无关）</td></tr>
 *   <tr><td>其他/未知</td><td>fail-closed 403</td></tr>
 * </table>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagPermissionService {

    /** 上传被拒时的统一文案：不区分「别家项目 / 厂家只读」，避免侧信道泄漏归属关系。 */
    private static final String UPLOAD_FORBIDDEN_MSG = "没有权限上传该项目的招标文件";

    private final TenderProjectMapper tenderProjectMapper;
    private final TenderUnitResolver tenderUnitResolver;

    /**
     * 校验当前用户能否对该项目提问。
     *
     * <p>只负责「问」。上传走 {@link #assertCanUpload(Long)}，不要在这里加写权限逻辑。
     *
     * @throws BusinessException 404 项目不存在 / 403 越权或保密期 / 401 未登录
     */
    public TenderProject assertCanAsk(Long tenderId) {
        // @TableLogic 已自动过滤 deleted=1
        TenderProject project = tenderProjectMapper.selectById(tenderId);
        if (project == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标项目不存在");
        }

        String role = SecurityUtil.getCurrentRole();
        if (role == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        if (Roles.ADMIN.equalsIgnoreCase(role)) {
            return project;
        }

        if (Roles.TENDER.equalsIgnoreCase(role)) {
            Long myUnitId = tenderUnitResolver.currentTenderUnitId();
            if (myUnitId == null || !myUnitId.equals(project.getTenderUnitId())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "非本单位项目");
            }
            return project;
        }

        if (Roles.MANUFACTURER.equalsIgnoreCase(role)) {
            if ("cancelled".equalsIgnoreCase(project.getStatus())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "项目已取消");
            }
            if (isInConfidential(project)) {
                throw new BusinessException(ResultCode.FORBIDDEN, "保密期内不可提问");
            }
            return project;
        }

        log.warn("未知角色拒绝 role={} userId={}", role, SecurityUtil.getCurrentUserId());
        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    /**
     * 校验当前用户能否向该项目上传招标文件（写权限）。
     *
     * <p>与 {@link #assertCanAsk(Long)} 刻意分开：提问是读，上传是写，写权限只给招标方。
     * 复用提问判定会让任一投标方（manufacturer）向任意 open 项目灌文件与向量 chunk。
     *
     * <p>归属判定只能走 {@link TenderUnitResolver#currentTenderUnitId()} 反查出
     * {@code tender_units.id} 再比 {@code tender_projects.tender_unit_id}；
     * {@code users.id} 与 {@code tender_units.id} 是两套主键，拿 userId 直接比会稳定误判。
     *
     * @return 命中的项目（调用方一般不用，保留与 {@code assertCanAsk} 一致的手感）
     * @throws BusinessException 404 项目不存在（{@code deleted=1} 同 404）/ 403 无权上传 / 401 未登录
     */
    public TenderProject assertCanUpload(Long tenderId) {
        if (tenderId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "tender_id 不能为空");
        }
        // @TableLogic 已自动过滤 deleted=1，已删除项目与不存在走同一条路径（统一 404）
        TenderProject project = tenderProjectMapper.selectById(tenderId);
        if (project == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标项目不存在");
        }
        String role = SecurityUtil.getCurrentRole();   // 未登录 → 401
        String userId = String.valueOf(SecurityUtil.getCurrentUserId());
        return checkUpload(role, userId, project);
    }

    /**
     * 契约签名（设计约定）：由调用方显式传入身份，不读当前线程的 SecurityContext。
     *
     * <p>controller 走 {@link #assertCanUpload(Long)} 的便捷重载；本重载留给跨线程 / 测试场景。
     * {@code userId} 只进日志，绝不参与归属比对（看类注释里的两套主键坑）。
     *
     * @throws BusinessException tenderId 非数字 → 400；其余同 {@link #assertCanUpload(Long)}
     */
    public TenderProject assertCanUpload(String userId, String role, String tenderId) {
        if (tenderId == null || tenderId.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "tender_id 不能为空");
        }
        long parsed;
        try {
            parsed = Long.parseLong(tenderId.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "tender_id 必须是数字");
        }
        TenderProject project = tenderProjectMapper.selectById(parsed);
        if (project == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "招标项目不存在");
        }
        return checkUpload(role, userId, project);
    }

    /**
     * 上传判定的唯一实现：角色分支一律 {@code equalsIgnoreCase} + if/else，
     * 不用 switch（库里 role 是小写，switch 精确匹配会全落 default → 全员 403）。
     */
    private TenderProject checkUpload(String role, String userId, TenderProject project) {
        if (role == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        if (Roles.ADMIN.equalsIgnoreCase(role)) {
            return project;
        }

        if (Roles.TENDER.equalsIgnoreCase(role)) {
            // 反查本单位 tender_units.id 再比项目的 tender_unit_id，绝不用 userId 直接比
            Long myUnitId = tenderUnitResolver.currentTenderUnitId();
            if (myUnitId == null || !myUnitId.equals(project.getTenderUnitId())) {
                log.warn("上传拒绝（非本单位项目）role={} userId={} tenderId={} myUnitId={} ownerUnitId={}",
                        role, userId, project.getId(), myUnitId, project.getTenderUnitId());
                throw new BusinessException(ResultCode.FORBIDDEN, UPLOAD_FORBIDDEN_MSG);
            }
            return project;
        }

        if (Roles.MANUFACTURER.equalsIgnoreCase(role)) {
            // 厂家只读：与项目状态（open/closed/cancelled/保密期）完全无关，一律拦在写入口
            log.warn("上传拒绝（厂家只读）role={} userId={} tenderId={}", role, userId, project.getId());
            throw new BusinessException(ResultCode.FORBIDDEN, UPLOAD_FORBIDDEN_MSG);
        }

        log.warn("上传拒绝（未知角色）role={} userId={} tenderId={}", role, userId, project.getId());
        throw new BusinessException(ResultCode.FORBIDDEN, UPLOAD_FORBIDDEN_MSG);
    }

    /** 保密期：{@code confidential_until} 为 NULL 表示不限制。 */
    public boolean isInConfidential(TenderProject project) {
        LocalDateTime until = project.getConfidentialUntil();
        return until != null && until.isAfter(LocalDateTime.now());
    }
}
