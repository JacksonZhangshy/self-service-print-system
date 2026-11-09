package com.printsys.service;

import com.printsys.config.PrintSysProperties;
import com.printsys.entity.*;
import com.printsys.pay.PaymentService;
import com.printsys.repo.PrintOrderRepository;
import com.printsys.repo.StoredFileRepository;
import com.printsys.util.BizException;
import com.printsys.util.OrderNoGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/** 订单创建、查询、取消。计价全部在服务端完成（不信任前端传价）。 */
@Service
@RequiredArgsConstructor
public class OrderService {
    private static final Set<String> COLOR_MODES = Set.of("BW", "COLOR");
    private static final Set<String> DUPLEX = Set.of("SINGLE", "DUPLEX");
    private static final Set<String> PAPERS = Set.of("A4", "A3");

    private final PrintOrderRepository orderRepository;
    private final StoredFileRepository fileRepository;
    private final PriceService priceService;
    private final PriceCalculator priceCalculator;
    private final PaymentService paymentService;
    private final PrintSysProperties props;

    @Transactional
    public PrintOrder createOrder(User user, Long fileId, int copies, String colorMode,
                                  String duplex, String paperSize, String terminalId) {
        if (!COLOR_MODES.contains(colorMode)) {
            throw new BizException("colorMode 仅支持 BW/COLOR");
        }
        if (!DUPLEX.contains(duplex)) {
            throw new BizException("duplex 仅支持 SINGLE/DUPLEX");
        }
        if (!PAPERS.contains(paperSize)) {
            throw new BizException("paperSize 仅支持 A4/A3");
        }
        if (copies < 1 || copies > 100) {
            throw new BizException("份数需在 1~100 之间");
        }
        StoredFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new BizException(404, "文件不存在"));
        if (!file.getUser().getId().equals(user.getId())) {
            throw new BizException(403, "只能打印自己上传的文件");
        }
        if (file.isPurged()) {
            throw new BizException(410, "文件已过保留期被清理，请重新上传");
        }
        PriceConfig cfg = priceService.current();
        BigDecimal amount = priceCalculator.calculate(cfg, colorMode, paperSize, duplex,
                file.getPageCount(), copies);

        PrintOrder order = new PrintOrder();
        order.setOrderNo(OrderNoGenerator.next());
        order.setUser(user);
        order.setFile(file);
        order.setTerminalId(normalizeTerminal(terminalId));
        order.setCopies(copies);
        order.setColorMode(colorMode);
        order.setDuplex(duplex);
        order.setPaperSize(paperSize);
        order.setPageCount(file.getPageCount());
        order.setAmount(amount);
        order.setStatus(OrderStatus.CREATED);
        order.setExpireAt(LocalDateTime.now().plusMinutes(props.getOrder().getExpireMinutes()));
        order.setRefundStatus(RefundStatus.NONE);
        return orderRepository.save(order);
    }

    /** 对外返回订单前必须走 fetch 查询：控制器会在事务外把它映射成 OrderView */
    public PrintOrder getOwnOrder(User user, String orderNo) {
        PrintOrder order = orderRepository.findByOrderNoFetch(orderNo)
                .orElseThrow(() -> new BizException(404, "订单不存在"));
        if (!order.getUser().getId().equals(user.getId())) {
            throw new BizException(403, "无权查看他人订单");
        }
        return order;
    }

    /** 用户取消未支付订单 */
    @Transactional
    public PrintOrder cancel(User user, String orderNo) {
        PrintOrder order = getOwnOrder(user, orderNo);
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.PAYING) {
            throw new BizException(409, "当前状态不可取消");
        }
        paymentService.closeOrder(order, OrderStatus.CANCELLED, "用户取消");
        return orderRepository.findByOrderNoFetch(orderNo).orElse(order);
    }

    private String normalizeTerminal(String terminalId) {
        if (terminalId == null) {
            return null;
        }
        String t = terminalId.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > 64 ? t.substring(0, 64) : t;
    }
}
