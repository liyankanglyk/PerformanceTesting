package com.xiaohua.performancetesting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaohua.performancetesting.entity.User;
import org.apache.ibatis.annotations.Mapper;

/** 用户表 Mapper。查询全部走 MyBatis-Plus 的 LambdaQueryWrapper，无自定义 SQL。 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
