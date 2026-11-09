package com.printsys.pay;

import com.printsys.entity.PayMethod;
import com.printsys.entity.PrintOrder;

import java.math.BigDecimal;
import java.util.Optional;

/** 支付渠道抽象：预下单、主动查询、关单、退款。每个渠道按配置自动 REAL / MOCK。 */
public interface PaymentGateway {

    PayMethod method();

    boolean isRealMode();

    PrecreateResult precreate(PrintOrder order);

    /** 主动查询兜底：已支付返回渠道交易号 */
    Optional<String> queryPaidTradeNo(PrintOrder order);

    /** 关单（超时/取消时释放二维码），失败只记日志不抛 */
    void close(PrintOrder order);

    /** 原路退款 */
    RefundResult refund(PrintOrder order, BigDecimal amount, String refundNo, String reason);
}
