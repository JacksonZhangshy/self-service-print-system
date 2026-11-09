package com.printsys.pay;

/** 预下单结果：qrContent 为二维码内容，mode 为 REAL / MOCK */
public record PrecreateResult(String qrContent, String mode) {
}
