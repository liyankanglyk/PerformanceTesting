package com.xiaohua.performancetesting.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.performancetesting.entity.Orders;

import java.util.List;

/**
 * 订单服务：下单（含扣库存）与订单查询。
 */
public interface OrderService extends IService<Orders> {

    /**
     * 下单：扣库存 + 写订单，同一事务。
     *
     * @param userId  买家 ID（取自 Token，不接受请求参数，防越权下单）
     * @param goodsId 商品 ID
     * @return 新订单（orderNo 已生成、status=0 未支付）
     * @throws RuntimeException {@code goods not found} 商品不存在 /
     *                          {@code insufficient stock} 库存不足；由全局异常处理转成 400
     */
    Orders buy(Long userId, Long goodsId);

    /**
     * 查某个用户的订单，按 createTs 倒序（最新在前）。
     *
     * @param keyword 订单号模糊匹配；为空返回该用户全部订单
     */
    List<Orders> listByUserId(Long userId, String keyword);

    /**
     * 按条件删除并返回**真实删除行数**。
     * IService.remove(Wrapper) 只给 boolean，用它写审计日志会记成“删了 1 条”这种假数字。
     */
    int deleteBy(Wrapper<Orders> wrapper);
}
