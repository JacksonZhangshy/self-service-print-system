package com.printsys.pay;

import com.printsys.config.PrintSysProperties;
import com.printsys.entity.*;
import com.printsys.repo.PrintOrderRepository;
import com.printsys.repo.PrintTaskRepository;
import com.printsys.service.RefundService;
import com.printsys.util.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 支付编排：发起支付、入账（金额与渠道双重校验 + 行锁幂等）、轮询兜底、关单。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    private final List<PaymentGateway> gatewayList;
    private final PrintOrderRepository orderRepository;
    private final PrintTaskRepository taskRepository;
    private final RefundService refundService;
    private final PrintSysProperties props;

    private Map<PayMethod, PaymentGateway> gateways;

    public PaymentGateway gateway(PayMethod method) {
        if (gateways == null) {
            gateways = new EnumMap<>(PayMethod.class);
            for (PaymentGateway g : gatewayList) {
                gateways.put(g.method(), g);
            }
        }
        PaymentGateway g = gateways.get(method);
        if (g == null) {
            throw new BizException("不支持的支付方式: " + method);
        }
        return g;
    }

    public boolean isRealMode(PayMethod method) {
        return gateway(method).isRealMode();
    }

    public boolean mockEnabled() {
        return props.getPay().isMockEnabled();
    }

    @Transactional
    public PrecreateResult startPayment(PrintOrder order, PayMethod method) {
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.PAYING) {
            throw new BizException(409, "订单当前状态不允许发起支付: " + order.getStatus());
        }
        if (order.getExpireAt() != null && order.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(409, "订单已超时关闭，请重新下单");
        }
        PaymentGateway gw = gateway(method);
        if (!gw.isRealMode() && !mockEnabled()) {
            throw new BizException(403, "该支付渠道尚未开通，请选择其他支付方式");
        }
        PrecreateResult result = gw.precreate(order);
        order.setPayMethod(method);
        order.setPayMode(result.mode());
        order.setStatus(OrderStatus.PAYING);
        orderRepository.save(order);
        return result;
    }

    /**
     * 支付成功入账（渠道回调 / 模拟确认 / 主动查询三条路径共用）。
     * 校验：渠道与订单一致、金额与订单一致；行锁保证并发安全；已入账幂等返回。
     */
    @Transactional
    public void handlePaid(String orderNo, String tradeNo, BigDecimal paidAmount, PayMethod channel) {
        PrintOrder order = orderRepository.findByOrderNoForUpdate(orderNo)
                .orElseThrow(() -> new BizException(404, "订单不存在: " + orderNo));

        if (order.getPayMethod() != null && channel != null && order.getPayMethod() != channel) {
            log.error("支付渠道不匹配 orderNo={} orderChannel={} notifyChannel={}", orderNo, order.getPayMethod(), channel);
            throw new BizException(409, "支付渠道与订单不一致");
        }
        if (paidAmount != null && order.getAmount().compareTo(paidAmount) != 0) {
            log.error("支付金额不匹配 orderNo={} 应收={} 实收={}", orderNo, order.getAmount(), paidAmount);
            throw new BizException(409, "支付金额与订单金额不一致");
        }

        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.PRINTING
                || order.getStatus() == OrderStatus.PRINTED) {
            log.info("订单 {} 已入账，忽略重复支付通知", orderNo);
            return;
        }
        if (order.getStatus() == OrderStatus.EXPIRED || order.getStatus() == OrderStatus.CANCELLED) {
            // 关单后又收到付款成功：钱已经收了，记录并原路退回
            log.warn("订单 {} 已{}但仍收到付款，转自动退款", orderNo, order.getStatus());
            order.setStatus(OrderStatus.PAID);
            order.setTradeNo(tradeNo);
            order.setPaidAt(LocalDateTime.now());
            orderRepository.save(order);
            final PrintOrder paid = order;
            // 必须在事务提交后再退款：退款抢占需要更新同一行，同事务内会自锁
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            refundService.autoRefundOnPrintFailure(paid);
                        }
                    });
            return;
        }
        if (order.getRefundStatus() == RefundStatus.SUCCESS) {
            log.warn("订单 {} 已退款成功，忽略新的支付通知", orderNo);
            return;
        }

        order.setStatus(OrderStatus.PAID);
        order.setTradeNo(tradeNo);
        order.setPaidAt(LocalDateTime.now());
        orderRepository.save(order);

        if (taskRepository.findByOrderId(order.getId()).isEmpty()) {
            PrintTask task = new PrintTask();
            task.setOrder(order);
            task.setStatus(TaskStatus.PENDING);
            task.setTerminalId(order.getTerminalId());
            taskRepository.save(task);
        }
        log.info("订单 {} 支付成功入账，交易号 {}，金额 {}，已生成打印任务", orderNo, tradeNo, order.getAmount());
    }

    /** 轮询兜底：真实模式下向渠道核对，PAYING 订单若渠道已支付则补入账 */
    @Transactional
    public OrderStatus refreshPayStatus(PrintOrder order) {
        if (order.getStatus() == OrderStatus.PAYING && order.getPayMethod() != null) {
            PaymentGateway gw = gateway(order.getPayMethod());
            gw.queryPaidTradeNo(order).ifPresent(tradeNo -> {
                try {
                    handlePaid(order.getOrderNo(), tradeNo, order.getAmount(), order.getPayMethod());
                } catch (BizException e) {
                    log.error("轮询补入账失败 orderNo={}: {}", order.getOrderNo(), e.getMessage());
                }
            });
            return orderRepository.findByOrderNo(order.getOrderNo())
                    .map(PrintOrder::getStatus).orElse(order.getStatus());
        }
        return order.getStatus();
    }

    /** 关闭订单（超时/取消）：尽力关渠道单，然后把订单置为终态 */
    @Transactional
    public void closeOrder(PrintOrder order, OrderStatus target, String reason) {
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.PAYING) {
            return;
        }
        if (order.getPayMethod() != null) {
            try {
                gateway(order.getPayMethod()).close(order);
            } catch (Exception e) {
                log.warn("关单调用失败 orderNo={}: {}", order.getOrderNo(), e.getMessage());
            }
        }
        order.setStatus(target);
        order.setCancelReason(reason);
        orderRepository.save(order);
        log.info("订单 {} 关闭为 {}，原因 {}", order.getOrderNo(), target, reason);
    }
}
