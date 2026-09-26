package com.xiaohua.performancetesting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单表实体。一次下单生成一条，状态只有未支付 / 已支付两态（没有“已取消”，删单即物理删除）。
 */
@Data
@TableName("orders")
public class Orders {

    /** 自增主键，界面上显示的“订单 #ID”。接口寻址用的是 orderNo，不是它 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号：UUID 去掉横线，唯一索引。下单返回它，支付按它寻址（PUT /api/order/pay/{orderNo}） */
    private String orderNo;

    /** 买家 ID，下单时只从 Token 取。没有外键约束，账号被删后这里会悬空 —— 所以删用户要求先清掉它的订单 */
    private Long userId;

    /** 商品 ID，同样没有外键约束：商品被删后悬空，所以删商品要求先清掉关联订单 */
    private Long goodsId;

    /** 成交价，下单时对商品单价做快照；之后改商品价格不影响历史订单 */
    private BigDecimal payPrice;

    /**
     * 创建时间的毫秒时间戳冗余列，与 create_time 同源。
     *
     * <p>存在的意义：概览页算“近 N 分钟下单速率”和订单列表倒序翻页都用它，
     * 配合索引 idx_create_ts 避免按 DATETIME 做函数运算走全表扫。
     */
    private Long createTs;

    /** 支付状态：0=未支付 / 1=已支付。支付用条件 UPDATE（status=0 才改），重复点击不会重复计入 */
    private Integer status;
}
