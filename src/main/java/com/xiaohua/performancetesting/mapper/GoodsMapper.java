package com.xiaohua.performancetesting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaohua.performancetesting.entity.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

    /** Deduct stock atomically, returns affected rows (1=success, 0=stock insufficient) */
    @Update("UPDATE goods SET stock = stock - 1 WHERE id = #{goodsId} AND stock > 0")
    int deductStock(@Param("goodsId") Long goodsId);
}
