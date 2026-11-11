package com.bidding.service;

import com.bidding.common.PageResult;
import com.bidding.dto.TenderRequest;
import com.bidding.dto.TenderUpdateRequest;
import com.bidding.vo.TenderVO;

import java.util.List;

public interface TenderService {

    PageResult<TenderVO> listTenders(String keyword, String status, long page, long pageSize);

    TenderVO getTender(Long id);

    TenderVO createTender(TenderRequest request);

    void updateTender(Long id, TenderUpdateRequest request);

    void deleteTender(Long id);

    List<TenderVO> listMyTenders();
}
