package com.printsys.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 打印任务：与订单 1:1。领取采用「条件更新」保证多助手并发下只有一个成功；
 * deadlineAt 用于回收「领取后助手崩溃」的僵尸任务。
 */
@Getter
@Setter
@Entity
@Table(name = "print_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_task_order", columnNames = "order_id"),
        indexes = {
                @Index(name = "idx_task_status", columnList = "status"),
                @Index(name = "idx_task_deadline", columnList = "status,deadline_at")
        })
public class PrintTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private PrintOrder order;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private TaskStatus status = TaskStatus.PENDING;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    /** 任务下发的目标终端（来自订单），为空表示任意终端可领 */
    @Column(name = "terminal_id", length = 64)
    private String terminalId;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /** 打印机回传的实际出纸张数（用于对账，非计价） */
    @Column(name = "actual_pages")
    private Integer actualPages;

    @Column(name = "fail_reason", length = 500)
    private String failReason;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime claimedAt;

    private LocalDateTime finishedAt;

    /** 领取超时时间：超过该时间仍未回传则回收重试 */
    @Column(name = "deadline_at")
    private LocalDateTime deadlineAt;
}
