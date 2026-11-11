package com.bidding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bidding.entity.TenderProject;
import com.bidding.vo.TenderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 招标项目 Mapper。复杂联表 + 动态条件用自定义 SQL（@Select + <script>），
 * 分页由 MyBatis-Plus 分页插件自动完成（方法第一个参数传 Page）。
 */
@Mapper
public interface TenderProjectMapper extends BaseMapper<TenderProject> {

    /**
     * 分页查询招标项目（含招标单位名称）。
     * status/keyword 为空时不加对应条件（由 Service 先把空串归一为 null）。
     */
    @Select("<script>" +
            "SELECT tp.*, tu.company_name AS tender_unit_name " +
            "FROM tender_projects tp JOIN tender_units tu ON tp.tender_unit_id = tu.id " +
            "<where>" +
            "tp.deleted = 0" +
            "<if test='status != null'> AND tp.status = #{status}</if> " +
            "<if test='keyword != null'> AND (" +
            "tp.project_number LIKE CONCAT('%',#{keyword},'%') OR " +
            "tp.equipment_name LIKE CONCAT('%',#{keyword},'%') OR " +
            "tp.model LIKE CONCAT('%',#{keyword},'%') OR " +
            "tp.description LIKE CONCAT('%',#{keyword},'%') OR " +
            "tp.packaging_requirements LIKE CONCAT('%',#{keyword},'%') OR " +
            "tu.company_name LIKE CONCAT('%',#{keyword},'%'))</if> " +
            "</where>" +
            "ORDER BY tp.created_at DESC" +
            "</script>")
    IPage<TenderVO> selectTenderPage(IPage<TenderVO> page,
                                     @Param("status") String status,
                                     @Param("keyword") String keyword);

    @Select("SELECT tp.*, tu.company_name AS tender_unit_name " +
            "FROM tender_projects tp JOIN tender_units tu ON tp.tender_unit_id = tu.id " +
            "WHERE tp.id = #{id} AND tp.deleted = 0")
    TenderVO selectTenderVOById(@Param("id") Long id);

    /** 当前招标单位的全部项目 */
    @Select("SELECT tp.*, tu.company_name AS tender_unit_name " +
            "FROM tender_projects tp JOIN tender_units tu ON tp.tender_unit_id = tu.id " +
            "WHERE tp.tender_unit_id = #{tenderUnitId} AND tp.deleted = 0 " +
            "ORDER BY tp.created_at DESC")
    java.util.List<TenderVO> selectMyTenderVOList(@Param("tenderUnitId") Long tenderUnitId);
}
