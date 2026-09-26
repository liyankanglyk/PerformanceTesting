package com.xiaohua.performancetesting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品表实体。
 *
 * <p>注意字段名与 JSON 字段名不一致：库存这里叫 {@code stock}，
 * 但对外的 {@code /api/goods}、{@code /api/admin/goods} 返回的都是 {@code goodsStock}
 * （历史约定，前端与 JMeter 断言都按 goodsStock 取）。
 */
@Data
@TableName("goods")
public class Goods {

    /** 自增主键。下单请求体里的 goodsId 就是这个 id，两个名字指同一个值 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 商品名。**库上有唯一索引 goods_name**（utf8mb4_unicode_ci 排序，大小写不敏感），
     * 所以重名既由应用层先查一次拦下，也由数据库兜底（见 AdminController#createGoods 的
     * DuplicateKeyException 分支），300 并发同时新增同名商品只有一个能进去。
     */
    private String goodsName;

    /** 单价，单位元，对应 DECIMAL(10,2)。用 BigDecimal 而不是 double，避免压测统计金额出现浮点误差 */
    private BigDecimal price;

    /** 库存。0 表示售罄（概览页会标红）；扣减走条件 UPDATE，见 GoodsMapper#deductStock */
    private Integer stock;

    /** 入库时间；列表排序按 id 而不是它，见 GoodsServiceImpl#listAll */
    private LocalDateTime createTime;
}
