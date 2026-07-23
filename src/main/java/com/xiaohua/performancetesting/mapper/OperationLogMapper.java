package com.xiaohua.performancetesting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaohua.performancetesting.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
