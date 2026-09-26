package com.xiaohua.performancetesting.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.performancetesting.entity.Goods;
import com.xiaohua.performancetesting.mapper.GoodsMapper;
import com.xiaohua.performancetesting.service.GoodsService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 商品查询实现。只有一条列表查询，分页留给控制器用 MyBatis-Plus Page 做，
 * 避免“先 list 全表再内存截断”这种在压测后必炸的写法。
 */
@Service
public class GoodsServiceImpl extends ServiceImpl<GoodsMapper, Goods> implements GoodsService {

    /** 关键词为空返回全部。两个分支都按 id 升序，语义见接口注释。 */
    @Override
    public List<Goods> listAll(String keyword) {
        // 固定按 id 升序，否则删除/新增商品后管理端列表顺序会跳动
        if (!StringUtils.hasText(keyword)) {
            return list(new LambdaQueryWrapper<Goods>().orderByAsc(Goods::getId));
        }
        return list(new LambdaQueryWrapper<Goods>()
                .like(Goods::getGoodsName, keyword.trim())
                .orderByAsc(Goods::getId));
    }
}
