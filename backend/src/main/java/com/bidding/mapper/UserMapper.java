package com.bidding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.bidding.entity.User;
import com.bidding.vo.AdminUserVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 继承 BaseMapper 后自动获得增删改查、条件查询等通用方法，无需写 SQL。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 管理员：用户列表分页，LEFT JOIN 出厂家/招标单位的审核状态（管理员无对应档案，status 为 null）。
     */
    @Select("<script>" +
            "SELECT u.id, u.username, u.email, u.role, " +
            "COALESCE(mf.status, tu.status) AS status " +
            "FROM users u " +
            "LEFT JOIN manufacturers mf ON u.id = mf.user_id AND u.role = 'manufacturer' " +
            "LEFT JOIN tender_units tu ON u.id = tu.user_id AND u.role = 'tender' " +
            "<where>" +
            "<if test='role != null'> AND u.role = #{role}</if> " +
            "<if test='keyword != null'> AND u.username LIKE CONCAT('%',#{keyword},'%')</if> " +
            "</where>" +
            "ORDER BY u.created_at DESC" +
            "</script>")
    IPage<AdminUserVO> selectAdminUserPage(IPage<AdminUserVO> page,
                                           @Param("role") String role,
                                           @Param("keyword") String keyword);
}
