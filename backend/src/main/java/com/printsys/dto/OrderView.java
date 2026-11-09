package com.printsys.dto;

import com.printsys.entity.PrintOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 订单视图（对外 DTO，避免直接暴露实体与懒加载代理） */
public record OrderView(
        String orderNo,
        String userPhone,
        String fileName,
        Long fileId,
        int copies,
        String colorMode,
        String duplex,
        String paperSize,
        int pageCount,
        String pageSource,
        BigDecimal amount,
        String status,
        String payMethod,
        String payMode,
        String tradeNo,
        String terminalId,
        String createdAt,
        String paidAt,
        String expireAt,
        String refundStatus,
        BigDecimal refundAmount,
        String cancelReason,
        /** 剩余支付秒数（未支付时用于前端倒计时） */
        long paySecondsLeft) {

    public static OrderView of(PrintOrder o) {
        long left = 0;
        if (o.getExpireAt() != null && o.getStatus() != null
                && (o.getStatus().name().equals("CREATED") || o.getStatus().name().equals("PAYING"))) {
            left = Math.max(0, java.time.Duration.between(LocalDateTime.now(), o.getExpireAt()).getSeconds());
        }
        return new OrderView(
                o.getOrderNo(),
                o.getUser() == null ? null : o.getUser().getPhone(),
                o.getFile() == null ? null : o.getFile().getOriginalName(),
                o.getFile() == null ? null : o.getFile().getId(),
                o.getCopies(),
                o.getColorMode(),
                o.getDuplex(),
                o.getPaperSize(),
                o.getPageCount(),
                o.getFile() == null ? null : o.getFile().getPageSource(),
                o.getAmount(),
                o.getStatus() == null ? null : o.getStatus().name(),
                o.getPayMethod() == null ? null : o.getPayMethod().name(),
                o.getPayMode(),
                o.getTradeNo(),
                o.getTerminalId(),
                str(o.getCreatedAt()),
                str(o.getPaidAt()),
                str(o.getExpireAt()),
                o.getRefundStatus() == null ? null : o.getRefundStatus().name(),
                o.getRefundAmount(),
                o.getCancelReason(),
                left);
    }

    private static String str(LocalDateTime t) {
        return t == null ? null : t.toString();
    }
}
