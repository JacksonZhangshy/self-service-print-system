package com.printsys.service;

import com.printsys.config.PrintSysProperties;
import com.printsys.entity.*;
import com.printsys.pay.PaymentGateway;
import com.printsys.pay.RefundResult;
import com.printsys.repo.PrintOrderRepository;
import com.printsys.repo.RefundRecordRepository;
import com.printsys.util.BizException;
import com.printsys.util.OrderNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 退款：原路退回。
 * 流程：抢占退款（独立事务）→ 调渠道 HTTP（无事务、无行锁）→ 落库（独立事务）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {
    private final List<PaymentGateway> gatewayList;
    private final PrintOrderRepository orderRepository;
    private final RefundRecordRepository refundRepository;
    private final RefundTxSupport txSupport;
    private final PrintSysProperties props;
    private final AuditService auditService;

    private Map<PayMethod, PaymentGateway> gateways;

    private PaymentGateway gateway(PayMethod method) {
        if (gateways == null) {
            gateways = new EnumMap<>(PayMethod.class);
            for (PaymentGateway g : gatewayList) {
                gateways.put(g.method(), g);
            }
        }
        PaymentGateway g = method == null ? null : gateways.get(method);
        if (g == null) {
            throw new BizException("订单无支付渠道信息，无法退款");
        }
        return g;
    }

    /** 管理端发起整单退款 */
    public RefundRecord refund(String orderNo, String reason, String operator) {
        PrintOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, "订单不存在"));
        if (order.getStatus() == OrderStatus.REFUNDED) {
            throw new BizException(409, "该订单已退款");
        }
        if (order.getStatus() == OrderStatus.REFUNDING) {
            throw new BizException(409, "该订单退款处理中");
        }
        if (order.getPaidAt() == null) {
            throw new BizException("订单未支付，无需退款");
        }
        return doRefund(order, order.getAmount(), reason, operator);
    }

    /** 打印失败自动退款（配置开关控制） */
    public void autoRefundOnPrintFailure(PrintOrder order) {
        if (!props.getRefund().isAutoOnPrintFailure()) {
            return;
        }
        try {
            PrintOrder fresh = orderRepository.findByOrderNo(order.getOrderNo()).orElse(null);
            if (fresh == null || fresh.getPaidAt() == null || fresh.getRefundStatus() != RefundStatus.NONE) {
                return;
            }
            doRefund(fresh, fresh.getAmount(), "打印失败自动退款", "AUTO");
        } catch (Exception e) {
            log.error("订单 {} 自动退款失败，需人工处理", order.getOrderNo(), e);
        }
    }

    private RefundRecord doRefund(PrintOrder order, BigDecimal amount, String reason, String operator) {
        String refundNo = OrderNoGenerator.nextRefundNo();
        OrderStatus before = order.getStatus();

        int claimed = txSupport.claim(order.getOrderNo(), amount, refundNo);
        if (claimed == 0) {
            throw new BizException(409, "该订单已被退款或正在退款中");
        }

        PaymentGateway gw = gateway(order.getPayMethod());
        RefundResult result = gw.refund(order, amount, refundNo, reason);

        RefundTxSupport.Outcome outcome = result.success() ? RefundTxSupport.Outcome.SUCCESS
                : result.processing() ? RefundTxSupport.Outcome.PROCESSING
                : RefundTxSupport.Outcome.FAILED;

        RefundRecord rec = txSupport.finish(order.getOrderNo(), before, amount,
                order.getPayMethod() == null ? "-" : order.getPayMethod().name(),
                result.channelRefundNo(), reason, operator, result.message(), outcome);

        auditService.log(operator, "REFUND", order.getOrderNo(),
                "金额 " + amount + "，结果 " + rec.getStatus() + "，原因 " + reason);
        log.info("退款 orderNo={} amount={} result={} msg={}",
                order.getOrderNo(), amount, rec.getStatus(), result.message());
        return rec;
    }
}
