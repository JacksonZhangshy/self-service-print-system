package com.printsys.util;

import lombok.Getter;

/** 统一接口返回包装：{ code, msg, data }，code=0 成功 */
@Getter
public class ApiResponse<T> {
    private final int code;
    private final String msg;
    private final T data;

    private ApiResponse(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "success", null);
    }

    public static <T> ApiResponse<T> error(int code, String msg) {
        return new ApiResponse<>(code, msg, null);
    }
}
