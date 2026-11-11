package com.bidding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bidding.entity.Message;
import com.bidding.vo.MessageVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /** 公开留言列表 */
    @Select("SELECT m.id, m.title, m.content, m.created_at, m.is_public, u.username AS author " +
            "FROM messages m JOIN users u ON m.user_id = u.id " +
            "WHERE m.is_public = 1 AND m.deleted = 0 " +
            "ORDER BY m.created_at DESC")
    List<MessageVO> selectPublicMessages();

    @Select("SELECT m.id, m.title, m.content, m.created_at, m.is_public, u.username AS author " +
            "FROM messages m JOIN users u ON m.user_id = u.id " +
            "WHERE m.id = #{id} AND m.is_public = 1 AND m.deleted = 0")
    MessageVO selectPublicMessageById(@Param("id") Long id);

    /** 管理员：全部留言（含私密）分页 */
    @Select("SELECT m.id, m.title, m.content, m.created_at, m.is_public, u.username AS author " +
            "FROM messages m JOIN users u ON m.user_id = u.id " +
            "WHERE m.deleted = 0 ORDER BY m.created_at DESC")
    IPage<MessageVO> selectAdminMessagePage(IPage<MessageVO> page);
}
