package com.printsys.entity;

/** 退款状态：NONE 未退款 / PROCESSING 渠道受理中 / SUCCESS 退款成功 / FAILED 退款失败 */
public enum RefundStatus {
    NONE,
    PROCESSING,
    SUCCESS,
    FAILED
}
