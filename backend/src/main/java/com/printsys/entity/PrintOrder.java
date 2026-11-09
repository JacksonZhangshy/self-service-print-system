package com.printsys.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 打印订单：一次「选文件 + 参数 + 付款 + 打印」的完整业务单。 */
@Getter
@Setter
@Entity
@Table(name = "print_order",
        uniqueConstraints = @UniqueConstraint(name = "uk_order_no", columnNames = "order_no"),
        indexes = {
                @Index(name = "idx_order_user", columnList = "user_id"),
                @Index(name = "idx_order_status", columnList = "status"),
                @Index(name = "idx_order_paid_at", columnList = "paid_at"),
                @Index(name = "idx_order_expire", columnList = "status,expire_at")
        })
public class PrintOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户订单号，同时作为渠道 out_trade_no */
    @Column(name = "order_no", nullable = false, length = 40)
    private String orderNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private StoredFile file;

    /** 下单终端（公共电脑标识），为空表示任意终端可取件 */
    @Column(name = "terminal_id", length = 64)
    private String terminalId;

    @Column(nullable = false)
    private int copies = 1;

    @Column(name = "color_mode", length = 10, nullable = false)
    private String colorMode = "BW";

    @Column(length = 10, nullable = false)
    private String duplex = "SINGLE";

    @Column(name = "paper_size", length = 8, nullable = false)
    private String paperSize = "A4";

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    private OrderStatus status = OrderStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_method", length = 10)
    private PayMethod payMethod;

    /** 支付渠道模式快照：REAL / MOCK */
    @Column(name = "pay_mode", length = 8)
    private String payMode;

    @Column(name = "trade_no", length = 64)
    private String tradeNo;

    /** 支付超时时间：到点未支付自动关单 */
    @Column(name = "expire_at", nullable = false)
    private LocalDateTime expireAt;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime paidAt;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", length = 16, nullable = false)
    private RefundStatus refundStatus = RefundStatus.NONE;

    @Column(name = "refund_amount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_no", length = 64)
    private String refundNo;

    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;
}
