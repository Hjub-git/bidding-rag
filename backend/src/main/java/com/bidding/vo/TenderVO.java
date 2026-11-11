package com.bidding.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 招标项目出参（含招标单位名称，供前端直接展示）。
 */
@Data
public class TenderVO {

    private Long id;
    private String projectNumber;
    private Long tenderUnitId;
    private String equipmentName;
    private String model;
    private String packagingRequirements;
    private Integer quantity;
    private LocalDate deadline;
    private BigDecimal deposit;
    private String description;
    private String status;
    private String tenderUnitName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
