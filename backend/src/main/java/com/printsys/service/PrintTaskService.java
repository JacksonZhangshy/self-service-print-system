package com.printsys.service;

import com.printsys.config.PrintSysProperties;
import com.printsys.entity.*;
import com.printsys.repo.PrintOrderRepository;
import com.printsys.repo.PrintTaskRepository;
import com.printsys.util.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 打印任务流转。
 * 领取用「条件更新」（PENDING→PRINTING 且受影响行数为 1）保证并发下只有一个助手成功；
 * 领取后写入 deadline，超时未回传由定时任务回收重试，重试超限则判失败并自动退款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrintTaskService {
    private static final int CANDIDATE_SCAN = 8;

    private final PrintTaskRepository taskRepository;
    private final PrintOrderRepository orderRepository;
    private final RefundService refundService;
    private final PrintSysProperties props;

    /** 助手领取任务：返回 null 表示当前无任务 */
    @Transactional
    public PrintTask claimNext(String agentId, String terminalId) {
        List<PrintTask> candidates = taskRepository.findClaimCandidates(
                terminalId, PageRequest.of(0, CANDIDATE_SCAN));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plusMinutes(props.getTask().getClaimTimeoutMinutes());
        for (PrintTask candidate : candidates) {
            int claimed = taskRepository.tryClaim(candidate.getId(), agentId, now, deadline);
            if (claimed == 1) {
                PrintTask task = taskRepository.findByIdFetch(candidate.getId()).orElseThrow();
                PrintOrder order = task.getOrder();
                if (order.getStatus() == OrderStatus.PAID) {
                    order.setStatus(OrderStatus.PRINTING);
                    orderRepository.save(order);
                }
                log.info("助手 {} 领取任务 {}（订单 {}）", agentId, task.getId(), order.getOrderNo());
                return task;
            }
            // 被别人抢先领走，继续尝试下一个候选
        }
        return null;
    }

    /** 助手回传结果 */
    @Transactional
    public PrintTask report(Long taskId, String agentId, String status, String failReason, Integer actualPages) {
        PrintTask task = taskRepository.findByIdFetch(taskId)
                .orElseThrow(() -> new BizException(404, "打印任务不存在"));
        if (task.getAgentId() != null && agentId != null && !task.getAgentId().equals(agentId)) {
            throw new BizException(403, "该任务已被其他打印助手领取");
        }
        if (task.getStatus() != TaskStatus.PRINTING) {
            throw new BizException(409, "任务当前状态不允许回传结果: " + task.getStatus());
        }
        PrintOrder order = task.getOrder();
        boolean needRefund = false;
        switch (status) {
            case "SUCCESS" -> {
                task.setStatus(TaskStatus.SUCCESS);
                task.setActualPages(actualPages);
                order.setStatus(OrderStatus.PRINTED);
            }
            case "FAILED" -> {
                task.setStatus(TaskStatus.FAILED);
                task.setFailReason(failReason == null ? "未知原因" : truncate(failReason, 500));
                order.setStatus(OrderStatus.PRINT_FAILED);
                needRefund = true;
            }
            default -> throw new BizException("status 仅支持 SUCCESS/FAILED");
        }
        task.setFinishedAt(LocalDateTime.now());
        orderRepository.save(order);
        PrintTask saved = taskRepository.save(task);

        if (needRefund) {
            PrintOrder finalOrder = order;
            afterCommit(() -> refundService.autoRefundOnPrintFailure(finalOrder));
        }
        // 回传结果要原样返回给助手，重新 fetch 保证关联已初始化
        return taskRepository.findByIdFetch(taskId).orElse(saved);
    }

    /** 定时任务：回收「领取后失联」的任务 */
    @Transactional
    public void reclaimTimedOut() {
        List<PrintTask> timedOut = taskRepository.findTimedOut(LocalDateTime.now(), PageRequest.of(0, 50));
        for (PrintTask t : timedOut) {
            int maxRetry = props.getTask().getMaxRetry();
            if (t.getRetryCount() >= maxRetry) {
                int n = taskRepository.markFailed(t.getId(), "打印终端失联，已达最大重试次数", LocalDateTime.now());
                if (n == 0) {
                    continue;
                }
                PrintTask failed = taskRepository.findById(t.getId()).orElse(null);
                if (failed == null) {
                    continue;
                }
                PrintOrder order = failed.getOrder();
                order.setStatus(OrderStatus.PRINT_FAILED);
                orderRepository.save(order);
                log.error("任务 {}（订单 {}）重试超限，判定打印失败并触发退款",
                        failed.getId(), order.getOrderNo());
                afterCommit(() -> refundService.autoRefundOnPrintFailure(order));
            } else {
                taskRepository.requeue(t.getId(), "打印终端超时未回传，已重新入队");
                log.warn("任务 {} 超时回收，重试次数 {}/{}", t.getId(), t.getRetryCount() + 1, maxRetry);
            }
        }
    }

    /** 管理端补打：为已支付订单重新生成一条任务（原任务置为失败） */
    @Transactional
    public PrintTask reprint(String orderNo, String operator) {
        PrintOrder order = orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BizException(404, "订单不存在"));
        if (order.getPaidAt() == null) {
            throw new BizException("订单未支付，不能补打");
        }
        if (order.getStatus() == OrderStatus.REFUNDED) {
            throw new BizException("订单已退款，如需打印请重新下单");
        }
        PrintTask task = taskRepository.findByOrderId(order.getId()).orElse(null);
        if (task != null && task.getStatus() == TaskStatus.PRINTING) {
            throw new BizException(409, "任务正在打印中，请等待结果");
        }
        if (task == null) {
            task = new PrintTask();
            task.setOrder(order);
        }
        task.setStatus(TaskStatus.PENDING);
        task.setAgentId(null);
        task.setClaimedAt(null);
        task.setDeadlineAt(null);
        task.setFinishedAt(null);
        task.setFailReason(null);
        task.setActualPages(null);
        task.setTerminalId(order.getTerminalId());
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);
        log.info("管理员 {} 对订单 {} 发起补打", operator, orderNo);
        PrintTask saved = taskRepository.save(task);
        return taskRepository.findByIdFetch(saved.getId()).orElse(saved);
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
