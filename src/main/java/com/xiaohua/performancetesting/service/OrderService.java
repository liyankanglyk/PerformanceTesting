package com.xiaohua.performancetesting.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.performancetesting.entity.Orders;

import java.util.List;

public interface OrderService extends IService<Orders> {

    Orders buy(Long userId, Long goodsId);

    List<Orders> listByUserId(Long userId, String keyword);

    /**
     * 按条件删除并返回**真实删除行数**。
     * IService.remove(Wrapper) 只给 boolean，用它写审计日志会记成“删了 1 条”这种假数字。
     */
    int deleteBy(Wrapper<Orders> wrapper);
}
