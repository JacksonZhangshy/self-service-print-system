package com.printsys.entity;

/**
 * 订单状态机：
 * <pre>
 * CREATED ──发起支付──▶ PAYING ──支付成功──▶ PAID ──助手领取──▶ PRINTING
 *    │                    │                                     ├─打印成功─▶ PRINTED
 *    │                    │                                     └─打印失败─▶ PRINT_FAILED ──自动退款──▶ REFUNDED
 *    └──超时/取消──▶ EXPIRED / CANCELLED
 * 已支付订单发起退款时进入 REFUNDING，退款成功后进入 REFUNDED。
 * </pre>
 */
public enum OrderStatus {
    CREATED,
    PAYING,
    PAID,
    PRINTING,
    PRINTED,
    PRINT_FAILED,
    CANCELLED,
    EXPIRED,
    REFUNDING,
    REFUNDED
}
