package com.xiaohua.performancetesting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaohua.performancetesting.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

/** 操作日志表 Mapper。翻页查询见 OperationLogService#pageQuery。 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> {
}
