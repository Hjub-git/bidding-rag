package com.bidding.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 发布/修改招标项目入参。创建和更新复用同一个 DTO（更新时后端用 COALESCE 只改传入字段）。
 */
@Data
public class TenderRequest {

    @NotBlank(message = "项目编号不能为空")
    private String projectNumber;

    @NotBlank(message = "设备名称不能为空")
    private String equipmentName;

    @NotBlank(message = "型号不能为空")
    private String model;

    private String packagingRequirements;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于 0")
    private Integer quantity;

    @NotNull(message = "截止日期不能为空")
    private LocalDate deadline;

    @NotNull(message = "保证金不能为空")
    @DecimalMin(value = "0", message = "保证金不能为负")
    private BigDecimal deposit;

    private String description;

    /** open / closed / cancelled，更新时可选 */
    private String status;
}
