package com.bidding.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 投标表。一个厂家对同一个招标项目只允许一条（数据库层有 UNIQUE 约束兜底）。
 */
@Data
@TableName("bids")
public class Bid {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenderProjectId;
    private Long manufacturerId;
    private LocalDate bidDate;
    private LocalDate productionDate;
    private String warrantyPeriod;
    private BigDecimal totalAmount;
    private LocalDate depositPaymentDate;
    private String bidDocument;

    /** submitted / accepted / rejected */
    private String status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
