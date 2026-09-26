package com.xiaohua.performancetesting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaohua.performancetesting.entity.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 商品表 Mapper。库存增减只有这一个自定义 SQL。 */
@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

    /**
     * 扣 1 件库存，返回受影响行数：1=成功，0=库存不足（或商品不存在）。
     *
     * <p>{@code AND stock > 0} 是关键：把判断压进一条 UPDATE，由数据库行锁保证原子性。
     * 先查库存再写回在 300 并发下会超卖；不要改成“select + update”两步。
     */
    @Update("UPDATE goods SET stock = stock - 1 WHERE id = #{goodsId} AND stock > 0")
    int deductStock(@Param("goodsId") Long goodsId);
}
