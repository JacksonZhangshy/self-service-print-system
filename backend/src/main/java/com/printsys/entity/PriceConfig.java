package com.printsys.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收费标准（单行配置，id 固定 1）。
 * 总价 = 单价(彩色模式 × 纸张) × 页数 × 份数 × (双面 ? 双面系数 : 1)，再与最低消费取大。
 */
@Getter
@Setter
@Entity
@Table(name = "price_config")
public class PriceConfig {
    @Id
    private Long id = 1L;

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal bwA4 = new BigDecimal("0.30");

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal bwA3 = new BigDecimal("0.60");

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal colorA4 = new BigDecimal("1.00");

    @Column(precision = 10, scale = 2, nullable = false)
    private BigDecimal colorA3 = new BigDecimal("2.00");

    /** 双面系数：0.50 = 双面半价，1.00 = 不打折 */
    @Column(name = "duplex_discount", precision = 5, scale = 2, nullable = false)
    private BigDecimal duplexDiscount = new BigDecimal("0.90");

    /** 每单最低收费（元），避免 1 页 0.3 元的小额支付被渠道拒单 */
    @Column(name = "min_charge", precision = 10, scale = 2, nullable = false)
    private BigDecimal minCharge = new BigDecimal("0.30");

    private LocalDateTime updatedAt = LocalDateTime.now();
}
