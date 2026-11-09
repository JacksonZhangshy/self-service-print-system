package com.printsys.util;

import lombok.Getter;

/** 业务异常：code 使用 HTTP 语义（400/401/403/404/409），由全局处理器映射为对应状态码。 */
@Getter
public class BizException extends RuntimeException {
    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
