package com.xiaohua.performancetesting.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.performancetesting.entity.Goods;

import java.util.List;

public interface GoodsService extends IService<Goods> {

    List<Goods> listAll(String keyword);
}
