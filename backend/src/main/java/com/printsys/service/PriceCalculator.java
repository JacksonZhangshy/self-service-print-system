package com.printsys.service;

import com.printsys.entity.PriceConfig;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 计价：总价 = 单价(彩色模式 × 纸张) × 页数 × 份数 × (双面 ? 双面系数 : 1)，再与最低消费取大。
 * 纯函数，便于单测覆盖。
 */
@Component
public class PriceCalculator {

    public BigDecimal unitPrice(PriceConfig cfg, String colorMode, String paperSize) {
        boolean color = "COLOR".equalsIgnoreCase(colorMode);
        boolean a3 = "A3".equalsIgnoreCase(paperSize);
        if (color) {
            return a3 ? cfg.getColorA3() : cfg.getColorA4();
        }
        return a3 ? cfg.getBwA3() : cfg.getBwA4();
    }

    public BigDecimal calculate(PriceConfig cfg, String colorMode, String paperSize,
                                String duplex, int pageCount, int copies) {
        if (pageCount <= 0) {
            throw new IllegalArgumentException("页数必须大于 0");
        }
        if (copies <= 0) {
            throw new IllegalArgumentException("份数必须大于 0");
        }
        BigDecimal total = unitPrice(cfg, colorMode, paperSize)
                .multiply(BigDecimal.valueOf(pageCount))
                .multiply(BigDecimal.valueOf(copies));
        if ("DUPLEX".equalsIgnoreCase(duplex)) {
            total = total.multiply(cfg.getDuplexDiscount());
        }
        BigDecimal min = cfg.getMinCharge() == null ? BigDecimal.ZERO : cfg.getMinCharge();
        if (total.compareTo(min) < 0) {
            total = min;
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }
}
