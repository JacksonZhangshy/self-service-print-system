package com.printsys;

import com.printsys.entity.PriceConfig;
import com.printsys.service.PriceCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 计价矩阵：黑白/彩色 × A4/A3 × 单/双面 + 取整 + 最低消费 + 非法入参 */
class PriceCalculatorTest {
    private final PriceCalculator calc = new PriceCalculator();
    private final PriceConfig cfg = new PriceConfig();

    @Test
    void blackWhiteA4Single() {
        assertEquals(new BigDecimal("6.00"),
                calc.calculate(cfg, "BW", "A4", "SINGLE", 10, 2));
    }

    @Test
    void colorA3DuplexDiscount() {
        assertEquals(new BigDecimal("9.00"),
                calc.calculate(cfg, "COLOR", "A3", "DUPLEX", 5, 1));
    }

    @Test
    void roundHalfUp() {
        assertEquals(new BigDecimal("0.81"),
                calc.calculate(cfg, "BW", "A4", "DUPLEX", 3, 1));
    }

    @Test
    void minChargeApplied() {
        PriceConfig c = new PriceConfig();
        c.setBwA4(new BigDecimal("0.10"));
        c.setMinCharge(new BigDecimal("0.50"));
        assertEquals(new BigDecimal("0.50"), calc.calculate(c, "BW", "A4", "SINGLE", 1, 1));
    }

    @Test
    void illegalArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> calc.calculate(cfg, "BW", "A4", "SINGLE", 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> calc.calculate(cfg, "BW", "A4", "SINGLE", 1, 0));
    }
}
