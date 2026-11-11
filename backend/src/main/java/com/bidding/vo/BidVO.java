package com.bidding.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 投标出参（招标单位/管理员视角，含厂家名称）。
 */
@Data
public class BidVO {

    private Long id;
    private Long tenderProjectId;
    private LocalDate bidDate;
    private LocalDate productionDate;
    private String warrantyPeriod;
    private BigDecimal totalAmount;
    private LocalDate depositPaymentDate;
    private String status;
    private String manufacturerName;
}
