package com.bidding.service;

import com.bidding.dto.BidRequest;
import com.bidding.dto.BidUpdateRequest;
import com.bidding.vo.BidVO;
import com.bidding.vo.MyBidVO;

import java.util.List;

public interface BidService {

    Long createBid(BidRequest request);

    List<BidVO> listBids(Long tenderProjectId);

    List<MyBidVO> listMyBids();

    void updateBid(Long id, BidUpdateRequest request);

    void withdrawBid(Long id);

    void acceptBid(Long id);

    void rejectBid(Long id);
}
