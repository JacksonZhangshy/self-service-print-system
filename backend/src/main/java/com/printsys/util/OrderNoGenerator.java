package com.printsys.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/** 商户订单号：PS + yyyyMMddHHmmss + 6 位随机十六进制（24 字符，满足支付宝 64 / 微信 32 位限制） */
public final class OrderNoGenerator {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private OrderNoGenerator() {
    }

    public static String next() {
        return "PS" + LocalDateTime.now().format(FMT)
                + String.format("%06x", ThreadLocalRandom.current().nextInt(0xFFFFFF));
    }

    /** 退款单号：RF + 时间 + 随机 */
    public static String nextRefundNo() {
        return "RF" + LocalDateTime.now().format(FMT)
                + String.format("%06x", ThreadLocalRandom.current().nextInt(0xFFFFFF));
    }
}
