package com.bidding.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 修改投标入参（厂家仅能改自己“未处理”的投标，且不能改项目归属）。
 */
@Data
public class BidUpdateRequest {

    @NotNull(message = "生产日期不能为空")
    private LocalDate productionDate;

    @NotBlank(message = "质保期不能为空")
    private String warrantyPeriod;

    @NotNull(message = "投标总金额不能为空")
    @DecimalMin(value = "0", message = "总金额不能为负")
    private BigDecimal totalAmount;

    @NotNull(message = "保证金缴纳日期不能为空")
    private LocalDate depositPaymentDate;

    private String bidDocument;
}
