package com.bidding.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 修改招标项目入参（部分更新）。
 * 与创建不同：所有字段都可选——管理员可能只改 status，招标单位也可能不改 project_number。
 * 后端用 COALESCE 语义（MyBatis-Plus updateById 忽略 null 字段）只更新传入的字段。
 */
@Data
public class TenderUpdateRequest {

    private String projectNumber;
    private String equipmentName;
    private String model;
    private String packagingRequirements;
    private Integer quantity;
    private LocalDate deadline;
    private BigDecimal deposit;
    private String description;

    /**
     * open / closed / cancelled，更新时可选。
     *
     * <p>状态机：这里用注解先收口成白名单（非法值直接 400，不进 Service）；
     * Service 里还有一道同样的白名单 + 状态流转规则，防注解被绕过。
     */
    @Pattern(regexp = "open|closed|cancelled", message = "状态只能是 open / closed / cancelled")
    private String status;
}
