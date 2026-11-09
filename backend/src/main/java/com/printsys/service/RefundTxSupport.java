package com.printsys.service;

import com.printsys.entity.OrderStatus;
import com.printsys.entity.PrintOrder;
import com.printsys.entity.RefundRecord;
import com.printsys.entity.RefundStatus;
import com.printsys.repo.PrintOrderRepository;
import com.printsys.repo.RefundRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款的两个独立事务：
 * 1) claim —— 抢占退款（条件更新），事务立刻提交，渠道 HTTP 调用期间不持有任何行锁；
 * 2) finish —— 落退款流水并推进订单状态。
 */
@Service
@RequiredArgsConstructor
public class RefundTxSupport {

    public enum Outcome {SUCCESS, PROCESSING, FAILED}

    private final PrintOrderRepository orderRepository;
    private final RefundRecordRepository refundRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int claim(String orderNo, BigDecimal amount, String refundNo) {
        return orderRepository.claimRefund(orderNo, amount, refundNo);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundRecord finish(String orderNo, OrderStatus before, BigDecimal amount, String channel,
                               String channelRefundNo, String reason, String operator,
                               String errorMsg, Outcome outcome) {
        RefundRecord rec = new RefundRecord();
        rec.setOrderNo(orderNo);
        rec.setAmount(amount);
        rec.setChannel(channel);
        rec.setChannelRefundNo(channelRefundNo);
        rec.setReason(reason);
        rec.setOperator(operator);

        PrintOrder order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        switch (outcome) {
            case SUCCESS -> {
                rec.setStatus("SUCCESS");
                rec.setFinishedAt(LocalDateTime.now());
                order.setStatus(OrderStatus.REFUNDED);
                order.setRefundStatus(RefundStatus.SUCCESS);
                order.setRefundedAt(LocalDateTime.now());
                order.setCancelReason(reason);
            }
            case PROCESSING -> {
                rec.setStatus("PROCESSING");
                order.setStatus(OrderStatus.REFUNDING);
                order.setRefundStatus(RefundStatus.PROCESSING);
            }
            case FAILED -> {
                rec.setStatus("FAILED");
                rec.setErrorMsg(errorMsg);
                rec.setFinishedAt(LocalDateTime.now());
                order.setStatus(before);
                order.setRefundStatus(RefundStatus.FAILED);
                order.setRefundNo(null);
            }
        }
        orderRepository.save(order);
        return refundRepository.save(rec);
    }
}
