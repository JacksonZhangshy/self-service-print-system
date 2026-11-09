package com.printsys.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退款流水（一个订单可能有多次部分退款，本系统按整单退款使用） */
@Getter
@Setter
@Entity
@Table(name = "refund_record", indexes = {@Index(name = "idx_refund_order", columnList = "order_no")})
public class RefundRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 40)
    private String orderNo;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(length = 10, nullable = false)
    private String channel;

    /** 渠道退款单号 */
    @Column(name = "channel_refund_no", length = 64)
    private String channelRefundNo;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(length = 200)
    private String reason;

    /** 操作人：admin / AUTO */
    @Column(length = 64)
    private String operator;

    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime finishedAt;
}
