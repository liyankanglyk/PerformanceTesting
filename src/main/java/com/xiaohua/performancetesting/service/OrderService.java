package com.xiaohua.performancetesting.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.performancetesting.entity.Orders;

import java.util.List;

public interface OrderService extends IService<Orders> {

    Orders buy(Long userId, Long goodsId);

    List<Orders> listByUserId(Long userId, String keyword);
}
