package com.xiaohua.performancetesting.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xiaohua.performancetesting.entity.Goods;
import com.xiaohua.performancetesting.mapper.GoodsMapper;
import com.xiaohua.performancetesting.service.GoodsService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class GoodsServiceImpl extends ServiceImpl<GoodsMapper, Goods> implements GoodsService {

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
