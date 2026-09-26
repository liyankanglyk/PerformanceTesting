package com.xiaohua.performancetesting.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;

/**
 * 分页响应体。
 * 只暴露前端翻页需要的字段，不把 MyBatis-Plus IPage 的内部结构（orders/optimize...）泄到 API 契约里。
 */
@Data
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> records;
    /** 总记录数 */
    private long total;
    /** 当前页码，从 1 开始 */
    private long page;
    /** 每页条数 */
    private long size;
    /** 总页数 */
    private long pages;

    /** 从 MyBatis-Plus 的分页结果转换。只取五个字段，不把 IPage 的内部结构（orders、optimizeCountSql 等）暴露成 API 契约。 */
    public static <T> PageResult<T> of(IPage<T> p) {
        PageResult<T> r = new PageResult<>();
        r.records = p.getRecords();
        r.total = p.getTotal();
        r.page = p.getCurrent();
        r.size = p.getSize();
        r.pages = p.getPages();
        return r;
    }

    /**
     * 手工分页（先查全量、内存里切）时用的重载。
     *
     * <p>pages 在这里现算而不是从查询结果取；size 传 0 会得到 pages=0，不会抛除零。
     */
    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        PageResult<T> r = new PageResult<>();
        r.records = records;
        r.total = total;
        r.page = page;
        r.size = size;
        r.pages = size > 0 ? (total + size - 1) / size : 0;
        return r;
    }
}
