package com.bidding.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 提交投标入参。
 */
@Data
public class BidRequest {

    @NotNull(message = "招标项目不能为空")
    private Long tenderProjectId;

    /** 投标日期，缺省为当天 */
    private LocalDate bidDate;

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
