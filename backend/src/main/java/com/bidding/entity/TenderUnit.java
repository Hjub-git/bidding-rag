package com.bidding.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 招标单位档案表。
 */
@Data
@TableName("tender_units")
public class TenderUnit {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String companyName;
    private String legalPerson;
    private String contactPhone;
    private String address;
    private String businessLicense;
    private String companyProfile;

    /** active / pending / rejected */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
