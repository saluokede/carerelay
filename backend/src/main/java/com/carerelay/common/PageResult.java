package com.carerelay.common;

import java.util.List;

/** 分页返回体。 */
public record PageResult<T>(List<T> list, long total, long page, long size) {

    public static <T> PageResult<T> of(List<T> list, long total, long page, long size) {
        return new PageResult<>(list, total, page, size);
    }
}
