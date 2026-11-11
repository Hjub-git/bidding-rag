package com.bidding.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * “我的投标”出参（厂家视角，含项目与招标单位信息）。
 */
@Data
public class MyBidVO {

    private Long id;
    private Long tenderProjectId;
    private LocalDate bidDate;
    private LocalDate productionDate;
    private String warrantyPeriod;
    private BigDecimal totalAmount;
    private LocalDate depositPaymentDate;
    private String bidDocument;
    private String bidStatus;

    private String projectNumber;
    private String equipmentName;
    private String model;
    private Integer quantity;
    private LocalDate deadline;
    private String tenderStatus;
    private String tenderUnitName;
}
