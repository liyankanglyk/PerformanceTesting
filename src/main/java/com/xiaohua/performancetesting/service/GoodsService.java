package com.xiaohua.performancetesting.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.xiaohua.performancetesting.entity.Goods;

import java.util.List;

/**
 * 商品查询服务。分页由调用方用 MyBatis-Plus Page 完成（管理端），这里只负责条件与排序。
 */
public interface GoodsService extends IService<Goods> {

    /**
     * 按商品名模糊查询，固定 id 升序。
     *
     * @param keyword 商品名关键字；为空或全空白时返回全部
     * @return 匹配列表（顺序稳定，删除/新增不会让列表跳动）
     */
    List<Goods> listAll(String keyword);
}
