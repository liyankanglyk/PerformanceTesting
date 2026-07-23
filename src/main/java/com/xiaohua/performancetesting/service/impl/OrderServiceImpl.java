package com.xiaohua.performancetesting.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.performancetesting.entity.Goods;
import com.xiaohua.performancetesting.entity.Orders;
import com.xiaohua.performancetesting.mapper.GoodsMapper;
import com.xiaohua.performancetesting.mapper.OrdersMapper;
import com.xiaohua.performancetesting.service.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

@Service
public class OrderServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements OrderService {

    private final GoodsMapper goodsMapper;
    private final OrdersMapper ordersMapper;

    public OrderServiceImpl(GoodsMapper goodsMapper, OrdersMapper ordersMapper) {
        this.goodsMapper = goodsMapper;
        this.ordersMapper = ordersMapper;
    }

    @Override
    @Transactional
    public Orders buy(Long userId, Long goodsId) {
        int rows = goodsMapper.deductStock(goodsId);
        if (rows == 0) {
            throw new RuntimeException("insufficient stock");
        }

        Goods goods = goodsMapper.selectById(goodsId);

        Orders order = new Orders();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(userId);
        order.setGoodsId(goodsId);
        order.setPayPrice(goods.getPrice());
        order.setCreateTs(System.currentTimeMillis());
        order.setStatus(0);
        ordersMapper.insert(order);

        return order;
    }

    @Override
    public List<Orders> listByUserId(Long userId, String keyword) {
        LambdaQueryWrapper<Orders> wrapper = new LambdaQueryWrapper<Orders>()
                .eq(Orders::getUserId, userId)
                .orderByDesc(Orders::getCreateTs);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Orders::getOrderNo, keyword);
        }
        return ordersMapper.selectList(wrapper);
    }
}
