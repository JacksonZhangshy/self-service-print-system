package com.printsys;

import com.printsys.entity.*;
import com.printsys.pay.PaymentService;
import com.printsys.pay.PrecreateResult;
import com.printsys.repo.PrintOrderRepository;
import com.printsys.repo.PrintTaskRepository;
import com.printsys.repo.StoredFileRepository;
import com.printsys.repo.UserRepository;
import com.printsys.service.OrderService;
import com.printsys.service.PageCountService;
import com.printsys.service.PrintTaskService;
import com.printsys.util.BizException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 全链路 + 安全回归：建单 → 支付 → 入账 → 任务 → 领取 → 回传，以及金额/渠道/并发/幂等校验。 */
@SpringBootTest
@ActiveProfiles("demo")
class PaymentFlowTest {

    @Autowired UserRepository userRepository;
    @Autowired StoredFileRepository fileRepository;
    @Autowired PrintOrderRepository orderRepository;
    @Autowired PrintTaskRepository taskRepository;
    @Autowired OrderService orderService;
    @Autowired PaymentService paymentService;
    @Autowired PrintTaskService printTaskService;
    @Autowired PageCountService pageCountService;

    private User user(String phone) {
        User u = new User();
        u.setPhone(phone);
        return userRepository.save(u);
    }

    private StoredFile pdfFile(User u, int pages) throws Exception {
        File pdf = Files.createTempFile("flow-test", ".pdf").toFile();
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(pdf);
        }
        StoredFile sf = new StoredFile();
        sf.setUser(u);
        sf.setOriginalName("毕业论文.pdf");
        sf.setStoragePath(pdf.getAbsolutePath());
        sf.setPrintPath(pdf.getAbsolutePath());
        sf.setSizeBytes(pdf.length());
        sf.setExtension("pdf");
        sf.setPageCount(pageCountService.countPdfPages(pdf));
        sf.setPageSource("REAL");
        return fileRepository.save(sf);
    }

    @Test
    void fullFlow_orderToPrinted() throws Exception {
        User u = user("13900001111");
        StoredFile sf = pdfFile(u, 3);
        assertEquals(3, sf.getPageCount());

        // 黑白 A4 单面 3 页 × 2 份 = 0.30 × 3 × 2 = 1.80
        PrintOrder order = orderService.createOrder(u, sf.getId(), 2, "BW", "SINGLE", "A4", null);
        assertEquals(new BigDecimal("1.80"), order.getAmount());
        assertEquals(OrderStatus.CREATED, order.getStatus());
        assertNotNull(order.getExpireAt());

        PrecreateResult pay = paymentService.startPayment(order, PayMethod.ALIPAY);
        assertEquals("MOCK", pay.mode());
        assertTrue(pay.qrContent().contains("/mock-pay"));
        assertEquals(OrderStatus.PAYING,
                orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus());

        // 入账 + 幂等
        paymentService.handlePaid(order.getOrderNo(), "MOCK-001", order.getAmount(), PayMethod.ALIPAY);
        paymentService.handlePaid(order.getOrderNo(), "MOCK-001", order.getAmount(), PayMethod.ALIPAY);
        PrintOrder paid = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertEquals(OrderStatus.PAID, paid.getStatus());
        assertNotNull(paid.getPaidAt());
        assertEquals(1, taskRepository.findAll().stream()
                .filter(t -> t.getOrder().getId().equals(paid.getId())).count());

        PrintTask claimed = printTaskService.claimNext("agent-test", null);
        assertNotNull(claimed);
        assertEquals(TaskStatus.PRINTING, claimed.getStatus());
        assertEquals(OrderStatus.PRINTING,
                orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus());

        printTaskService.report(claimed.getId(), "agent-test", "SUCCESS", null, 3);
        assertEquals(TaskStatus.SUCCESS, taskRepository.findById(claimed.getId()).orElseThrow().getStatus());
        assertEquals(OrderStatus.PRINTED,
                orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus());
    }

    @Test
    void rejectWhenAmountMismatch() throws Exception {
        User u = user("13900002222");
        StoredFile sf = pdfFile(u, 2);
        PrintOrder order = orderService.createOrder(u, sf.getId(), 1, "BW", "SINGLE", "A4", null);
        paymentService.startPayment(order, PayMethod.ALIPAY);

        BizException e = assertThrows(BizException.class, () ->
                paymentService.handlePaid(order.getOrderNo(), "MOCK-X", new BigDecimal("0.01"), PayMethod.ALIPAY));
        assertTrue(e.getMessage().contains("金额"));
        assertEquals(OrderStatus.PAYING,
                orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus(),
                "金额不符时订单必须保持未支付");
    }

    @Test
    void rejectWhenChannelMismatch() throws Exception {
        User u = user("13900003333");
        StoredFile sf = pdfFile(u, 1);
        PrintOrder order = orderService.createOrder(u, sf.getId(), 1, "BW", "SINGLE", "A4", null);
        paymentService.startPayment(order, PayMethod.ALIPAY);

        assertThrows(BizException.class, () ->
                paymentService.handlePaid(order.getOrderNo(), "MOCK-X", order.getAmount(), PayMethod.WECHAT));
    }

    @Test
    void expireOrderCannotPay() throws Exception {
        User u = user("13900004444");
        StoredFile sf = pdfFile(u, 1);
        PrintOrder order = orderService.createOrder(u, sf.getId(), 1, "BW", "SINGLE", "A4", null);
        order.setExpireAt(java.time.LocalDateTime.now().minusMinutes(1));
        orderRepository.save(order);

        assertThrows(BizException.class, () -> paymentService.startPayment(order, PayMethod.ALIPAY));
    }

    /** 并发领取：8 个助手抢同一条任务，只能有 1 个成功（原实现 8 个全成功） */
    @Test
    void concurrentClaim_onlyOneWins() throws Exception {
        User u = user("13900005555");
        StoredFile sf = pdfFile(u, 1);
        PrintOrder order = orderService.createOrder(u, sf.getId(), 1, "BW", "SINGLE", "A4", null);
        paymentService.startPayment(order, PayMethod.ALIPAY);
        paymentService.handlePaid(order.getOrderNo(), "MOCK-C", order.getAmount(), PayMethod.ALIPAY);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Long> claimedIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    PrintTask t = printTaskService.claimNext("race-" + idx, null);
                    if (t != null && t.getOrder().getOrderNo().equals(order.getOrderNo())) {
                        success.incrementAndGet();
                        synchronized (claimedIds) {
                            claimedIds.add(t.getId());
                        }
                    }
                } catch (Exception ignored) {
                    // 领取失败是预期行为
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(success.get() <= 1, "同一任务只能被一个助手领取，实际 " + success.get() + " 个");
        assertTrue(claimedIds.stream().distinct().count() <= 1);
    }

    /** 补打：已打印订单可重新生成任务；已退款订单不允许白打 */
    @Test
    void reprintCreatesNewTask() throws Exception {
        User u = user("13900006666");
        StoredFile sf = pdfFile(u, 1);
        PrintOrder order = orderService.createOrder(u, sf.getId(), 1, "BW", "SINGLE", "A4", null);
        paymentService.startPayment(order, PayMethod.ALIPAY);
        paymentService.handlePaid(order.getOrderNo(), "MOCK-R", order.getAmount(), PayMethod.ALIPAY);
        PrintTask t = printTaskService.claimNext("agent-r", null);
        assertNotNull(t);
        printTaskService.report(t.getId(), "agent-r", "SUCCESS", null, 1);

        PrintTask again = printTaskService.reprint(order.getOrderNo(), "admin");
        assertEquals(TaskStatus.PENDING, again.getStatus());
        assertEquals(OrderStatus.PAID,
                orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow().getStatus());
    }

    /** 打印失败 → 自动退款 → 订单进入 REFUNDED，且不允许再补打（避免白打） */
    @Test
    void printFailureTriggersAutoRefund() throws Exception {
        User u = user("13900007777");
        StoredFile sf = pdfFile(u, 1);
        PrintOrder order = orderService.createOrder(u, sf.getId(), 1, "BW", "SINGLE", "A4", null);
        paymentService.startPayment(order, PayMethod.ALIPAY);
        paymentService.handlePaid(order.getOrderNo(), "MOCK-F", order.getAmount(), PayMethod.ALIPAY);
        PrintTask t = printTaskService.claimNext("agent-f", null);
        assertNotNull(t);
        printTaskService.report(t.getId(), "agent-f", "FAILED", "缺纸", null);

        PrintOrder after = orderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
        assertEquals(RefundStatus.SUCCESS, after.getRefundStatus());
        assertEquals(OrderStatus.REFUNDED, after.getStatus());
        assertThrows(BizException.class, () -> printTaskService.reprint(order.getOrderNo(), "admin"));
    }
}
