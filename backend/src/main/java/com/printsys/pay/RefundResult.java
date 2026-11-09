package com.printsys.pay;

/**
 * 渠道退款结果。
 *
 * @param success          已确认退款成功
 * @param processing       渠道已受理、结果异步（微信部分场景）
 * @param channelRefundNo  渠道退款单号
 * @param message          失败原因或渠道提示
 */
public record RefundResult(boolean success, boolean processing, String channelRefundNo, String message) {

    public static RefundResult ok(String channelRefundNo) {
        return new RefundResult(true, false, channelRefundNo, "退款成功");
    }

    public static RefundResult accepted(String channelRefundNo) {
        return new RefundResult(false, true, channelRefundNo, "渠道已受理，退款处理中");
    }

    public static RefundResult fail(String message) {
        return new RefundResult(false, false, null, message);
    }
}
