package com.xiaohua.performancetesting.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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

/**
 * 订单实现：下单扣库存 + 支付/查询。
 *
 * <p>这里是全项目唯一有并发压力的写路径（JMeter 300 并发打的就是下单），
 * 库存判定交给数据库的条件 UPDATE，而不是 Java 里的 if 判断。
 */
@Service
public class OrderServiceImpl extends ServiceImpl<OrdersMapper, Orders> implements OrderService {

    /** 显式持有是为了调用 deductStock 那个条件 UPDATE；商品查询也有用（取成交快照价格） */
    private final GoodsMapper goodsMapper;
    /** 与父类 ServiceImpl 内部是同一个 Mapper；这里显式持有是为了 delete(Wrapper) 拿真实删除行数 */
    private final OrdersMapper ordersMapper;

    /** 构造注入两个 Mapper：goodsMapper 只为 deductStock 的条件 UPDATE，ordersMapper 用于 delete(Wrapper) 取真实行数。 */
    public OrderServiceImpl(GoodsMapper goodsMapper, OrdersMapper ordersMapper) {
        this.goodsMapper = goodsMapper;
        this.ordersMapper = ordersMapper;
    }

    /**
     * 按条件删除并返回真实行数。
     *
     * <p>接口里已经说明了为什么不用 {@code IService.remove(Wrapper)}；
     * 另外调用方注意：每个条件都要新建 wrapper 对象，复用同一个会把 LIMIT 带进 DELETE。
     */
    @Override
    public int deleteBy(com.baomidou.mybatisplus.core.conditions.Wrapper<Orders> wrapper) {
        return ordersMapper.delete(wrapper);
    }

    /**
     * 下单：扣 1 件库存 + 插一条订单。
     *
     * <p>顺序是先扣后查：deductStock 返回 0 就直接失败，不做“先查库存够不够再扣”，
     * 那个两步写法在并发下必然超卖。成功路径只有一次 selectById（取价格做成交快照），
     * 只有失败时才多查一次主键用于区分“商品不存在”和“库存不足”。
     *
     * <p><b>关于 @Transactional</b>：初始化脚本已把四张表从 MyISAM 改成 <b>InnoDB</b>，
     * 所以这里的回滚是真生效的：插订单失败时，已扣的库存会随事务退回去。
     * 若某个库是旧脚本建的、表仍是 MyISAM，则回滚不生效（MyISAM 不支持事务），
     * 会出现“库存少了、订单没有”的缺口，参见 README 的“存储引擎”一行与迁移 SQL。
     * 防超卖两种引擎下都成立：deductStock 是单语句条件 UPDATE，不依赖事务。
     */
    @Override
    @Transactional
    public Orders buy(Long userId, Long goodsId) {
        int rows = goodsMapper.deductStock(goodsId);
        if (rows == 0) {
            // 只在失败分支多一次主键查询，区分“商品不存在”和“库存不足”（成功路径保持原有开销）
            Goods missing = goodsMapper.selectById(goodsId);
            throw new RuntimeException(missing == null ? "goods not found" : "insufficient stock");
        }

        // 取商品单价做成交快照；这一步已在同一事务内，价格与库存变动是一致的
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

    /**
     * 用户订单列表（不分页，用户端页面自己渲染）。
     * 排序用 createTs 而不是 id：id 是自增的，但压测时并发插入的顺序与创建顺序未必一致。
     */
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
