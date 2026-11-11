package com.bidding.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AdminStatisticsVO {

    private long users;
    private long manufacturers;
    private long tenderUnits;
    private long tenders;
    private long bids;
    private long messages;
}
