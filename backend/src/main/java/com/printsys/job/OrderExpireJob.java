package com.printsys.job;

import com.printsys.entity.OrderStatus;
import com.printsys.entity.PrintOrder;
import com.printsys.pay.PaymentService;
import com.printsys.repo.PrintOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 未支付订单到期关单：释放渠道二维码，避免「过了很久才付款却没人取件」。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpireJob {
    private final PrintOrderRepository orderRepository;
    private final PaymentService paymentService;

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void run() {
        List<PrintOrder> expired = orderRepository.findExpired(LocalDateTime.now(), PageRequest.of(0, 50));
        if (expired.isEmpty()) {
            return;
        }
        for (PrintOrder order : expired) {
            try {
                paymentService.closeOrder(order, OrderStatus.EXPIRED, "超时未支付");
            } catch (Exception e) {
                log.error("订单 {} 超时关单失败", order.getOrderNo(), e);
            }
        }
        log.info("超时关单 {} 笔", expired.size());
    }
}
