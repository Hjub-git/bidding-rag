package com.bidding.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 机电设备生产厂家档案表。
 */
@Data
@TableName("manufacturers")
public class Manufacturer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String companyName;
    private String legalPerson;
    private String contactPhone;
    private String address;
    private String businessLicense;
    private String companyProfile;

    /** active / pending / rejected —— 由管理员审核 */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
