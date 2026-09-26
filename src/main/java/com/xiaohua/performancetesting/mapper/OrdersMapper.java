package com.xiaohua.performancetesting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaohua.performancetesting.entity.Orders;
import org.apache.ibatis.annotations.Mapper;

/** 订单表 Mapper。deleteBy 需要真实删除行数时直接用 {@code delete(Wrapper)}，见 OrderService#deleteBy。 */
@Mapper
public interface OrdersMapper extends BaseMapper<Orders> {
}
