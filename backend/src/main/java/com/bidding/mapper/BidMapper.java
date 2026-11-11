package com.bidding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bidding.entity.Bid;
import com.bidding.vo.BidVO;
import com.bidding.vo.MyBidVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BidMapper extends BaseMapper<Bid> {

    /** 招标单位/管理员视角：某项目的投标列表（含厂家名称） */
    @Select("<script>" +
            "SELECT b.id, b.tender_project_id, b.bid_date, b.production_date, b.warranty_period, " +
            "b.total_amount, b.deposit_payment_date, b.status, m.company_name AS manufacturer_name " +
            "FROM bids b LEFT JOIN manufacturers m ON b.manufacturer_id = m.id " +
            "<where>" +
            "b.deleted = 0" +
            "<if test='tenderProjectId != null'> AND b.tender_project_id = #{tenderProjectId}</if> " +
            "</where>" +
            "ORDER BY b.created_at DESC" +
            "</script>")
    List<BidVO> selectBidVOList(@Param("tenderProjectId") Long tenderProjectId);

    /** 厂家视角：我的投标列表（含项目与招标单位信息） */
    @Select("SELECT b.id, b.tender_project_id, b.bid_date, b.production_date, b.warranty_period, " +
            "b.total_amount, b.deposit_payment_date, b.bid_document, b.status AS bid_status, " +
            "tp.project_number, tp.equipment_name, tp.model, tp.quantity, tp.deadline, " +
            "tp.status AS tender_status, tu.company_name AS tender_unit_name " +
            "FROM bids b " +
            "JOIN tender_projects tp ON b.tender_project_id = tp.id " +
            "JOIN tender_units tu ON tp.tender_unit_id = tu.id " +
            "WHERE b.manufacturer_id = #{manufacturerId} AND b.deleted = 0 " +
            "ORDER BY b.created_at DESC")
    List<MyBidVO> selectMyBidVOList(@Param("manufacturerId") Long manufacturerId);
}
