package com.bidding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bidding.entity.Reply;
import com.bidding.vo.ReplyVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ReplyMapper extends BaseMapper<Reply> {

    @Select("SELECT r.id, r.content, r.created_at, u.username AS author " +
            "FROM replies r JOIN users u ON r.user_id = u.id " +
            "WHERE r.message_id = #{messageId} AND r.deleted = 0 " +
            "ORDER BY r.created_at ASC")
    List<ReplyVO> selectReplyVOByMessageId(@Param("messageId") Long messageId);
}
