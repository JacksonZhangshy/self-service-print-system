package com.printsys.controller;

import com.alipay.api.internal.util.AlipaySignature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.printsys.config.PrintSysProperties;
import com.printsys.dto.Requests;
import com.printsys.entity.OrderStatus;
import com.printsys.entity.PayMethod;
import com.printsys.entity.PrintOrder;
import com.printsys.pay.PaymentService;
import com.printsys.pay.WechatGateway;
import com.printsys.repo.PrintOrderRepository;
import com.printsys.util.ApiResponse;
import com.printsys.util.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 支付回调与模拟支付确认。
 *
 * 安全基线（原实现的漏洞都在这里补上）：
 * 1) 支付宝：RSA2 验签 + app_id + seller_id + **订单金额** 三重校验；
 * 2) 微信：平台证书验签（按 Wechatpay-Serial 选证书，5 分钟时间窗防重放）
 *           + **mchid / appid / 订单金额** 校验；
 * 3) 模拟确认：必须「生产开关允许」+「订单本身是 MOCK 渠道」+「订单确已发起该渠道支付」，
 *    三者同时满足才可能入账——彻底堵掉「未支付订单直接变已支付」的免费打印通道。
 */
@Slf4j
@RestController
@RequestMapping("/api/pay")
@RequiredArgsConstructor
public class PayNotifyController {
    private final PaymentService paymentService;
    private final WechatGateway wechatGateway;
    private final PrintOrderRepository orderRepository;
    private final PrintSysProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------- 支付宝异步通知 --------------------
    @PostMapping("/alipay/notify")
    public String alipayNotify(HttpServletRequest request) {
        try {
            PrintSysProperties.Pay.Alipay a = props.getPay().getAlipay();
            if (!a.isRealConfigured()) {
                log.warn("收到支付宝通知但当前未启用真实支付宝渠道，忽略");
                return "failure";
            }
            Map<String, String> params = new HashMap<>();
            request.getParameterMap().forEach((k, v) -> params.put(k, String.join(",", v)));

            if (!AlipaySignature.rsaCheckV1(params, a.getAlipayPublicKey(), "UTF-8", "RSA2")) {
                log.warn("支付宝通知验签失败 outTradeNo={}", params.get("out_trade_no"));
                return "failure";
            }
            if (!a.getAppId().equals(params.get("app_id"))) {
                log.error("支付宝通知 app_id 不匹配 notify={} expected={}", params.get("app_id"), a.getAppId());
                return "failure";
            }
            if (a.getSellerId() != null && !a.getSellerId().isBlank()
                    && !a.getSellerId().equals(params.get("seller_id"))) {
                log.error("支付宝通知 seller_id 不匹配 notify={} expected={}",
                        params.get("seller_id"), a.getSellerId());
                return "failure";
            }

            String orderNo = params.get("out_trade_no");
            String status = params.get("trade_status");
            if (!"TRADE_SUCCESS".equals(status) && !"TRADE_FINISHED".equals(status)) {
                log.info("支付宝通知非成功状态 orderNo={} status={}", orderNo, status);
                return "success";
            }

            PrintOrder order = orderRepository.findByOrderNo(orderNo).orElse(null);
            if (order == null) {
                log.error("支付宝通知订单不存在 orderNo={}", orderNo);
                return "failure";
            }
            BigDecimal paid = new BigDecimal(params.get("total_amount"));
            if (order.getAmount().compareTo(paid) != 0) {
                log.error("支付宝通知金额不一致，拒绝入账 orderNo={} 应收={} 实收={}",
                        orderNo, order.getAmount(), paid);
                return "failure";
            }

            paymentService.handlePaid(orderNo, params.get("trade_no"), paid, PayMethod.ALIPAY);
            return "success";
        } catch (Exception e) {
            log.error("处理支付宝通知异常", e);
            return "failure";
        }
    }

    // -------------------- 微信支付异步通知 --------------------
    @PostMapping("/wechat/notify")
    public Map<String, String> wechatNotify(@RequestBody String body, HttpServletRequest request) {
        try {
            PrintSysProperties.Pay.Wechat w = props.getPay().getWechat();
            if (!w.isRealConfigured()) {
                log.warn("收到微信通知但当前未启用真实微信渠道，忽略");
                return Map.of("code", "FAIL", "message", "渠道未启用");
            }
            String timestamp = request.getHeader("Wechatpay-Timestamp");
            String nonce = request.getHeader("Wechatpay-Nonce");
            String signature = request.getHeader("Wechatpay-Signature");
            String serial = request.getHeader("Wechatpay-Serial");

            if (!wechatGateway.verifyNotify(timestamp, nonce, serial, signature, body)) {
                return Map.of("code", "FAIL", "message", "验签失败");
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode resource = root.get("resource");
            String plain = wechatGateway.decryptResource(
                    resource.path("nonce").asText(),
                    resource.path("associated_data").asText(""),
                    resource.path("ciphertext").asText());
            JsonNode txn = objectMapper.readTree(plain);

            String orderNo = txn.path("out_trade_no").asText();
            String mchid = txn.path("mchid").asText();
            String appid = txn.path("appid").asText();
            if (!w.getMchId().equals(mchid)) {
                log.error("微信通知商户号不匹配，拒绝处理 orderNo={} mchid={}", orderNo, mchid);
                return Map.of("code", "FAIL", "message", "商户号不匹配");
            }
            if (!w.getAppId().equals(appid)) {
                log.error("微信通知 appid 不匹配，拒绝处理 orderNo={} appid={}", orderNo, appid);
                return Map.of("code", "FAIL", "message", "appid 不匹配");
            }
            if (!"SUCCESS".equals(txn.path("trade_state").asText())) {
                log.info("微信通知非成功状态 orderNo={} state={}", orderNo, txn.path("trade_state").asText());
                return Map.of("code", "SUCCESS", "message", "成功");
            }

            PrintOrder order = orderRepository.findByOrderNo(orderNo).orElse(null);
            if (order == null) {
                log.error("微信通知订单不存在 orderNo={}", orderNo);
                return Map.of("code", "FAIL", "message", "订单不存在");
            }
            long cents = txn.path("amount").path("total").asLong(-1);
            BigDecimal paid = BigDecimal.valueOf(cents).movePointLeft(2);
            if (order.getAmount().compareTo(paid) != 0) {
                log.error("微信通知金额不一致，拒绝入账 orderNo={} 应收={} 实收={}",
                        orderNo, order.getAmount(), paid);
                return Map.of("code", "FAIL", "message", "金额不一致");
            }

            paymentService.handlePaid(orderNo, txn.path("transaction_id").asText(), paid, PayMethod.WECHAT);
            return Map.of("code", "SUCCESS", "message", "成功");
        } catch (Exception e) {
            log.error("处理微信通知异常", e);
            return Map.of("code", "FAIL", "message", "处理异常");
        }
    }

    /** 微信退款结果通知（可选启用；退款结果也会在发起退款时同步返回） */
    @PostMapping("/wechat/refund-notify")
    public Map<String, String> wechatRefundNotify(@RequestBody String body, HttpServletRequest request) {
        try {
            if (!wechatGateway.verifyNotify(
                    request.getHeader("Wechatpay-Timestamp"),
                    request.getHeader("Wechatpay-Nonce"),
                    request.getHeader("Wechatpay-Serial"),
                    request.getHeader("Wechatpay-Signature"),
                    body)) {
                return Map.of("code", "FAIL", "message", "验签失败");
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode resource = root.get("resource");
            String plain = wechatGateway.decryptResource(
                    resource.path("nonce").asText(),
                    resource.path("associated_data").asText(""),
                    resource.path("ciphertext").asText());
            JsonNode refund = objectMapper.readTree(plain);
            String orderNo = refund.path("out_trade_no").asText();
            String status = refund.path("refund_status").asText();
            log.info("微信退款通知 orderNo={} status={} refundId={}",
                    orderNo, status, refund.path("refund_id").asText());
            if ("SUCCESS".equals(status)) {
                orderRepository.findByOrderNo(orderNo).ifPresent(o -> {
                    if (o.getRefundStatus() != null
                            && o.getRefundStatus().name().equals("PROCESSING")) {
                        o.setRefundStatus(com.printsys.entity.RefundStatus.SUCCESS);
                        o.setStatus(OrderStatus.REFUNDED);
                        o.setRefundedAt(LocalDateTime.now());
                        orderRepository.save(o);
                    }
                });
            }
            return Map.of("code", "SUCCESS", "message", "成功");
        } catch (Exception e) {
            log.error("处理微信退款通知异常", e);
            return Map.of("code", "FAIL", "message", "处理异常");
        }
    }

    // -------------------- 模拟支付确认 --------------------
    @PostMapping("/mock/confirm")
    public ApiResponse<Map<String, String>> mockConfirm(@Valid @RequestBody Requests.MockConfirmRequest req) {
        if (!props.getPay().isMockEnabled()) {
            throw new BizException(403, "模拟支付已关闭");
        }
        PayMethod method;
        try {
            method = PayMethod.valueOf(req.method().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("method 仅支持 ALIPAY/WECHAT");
        }
        PrintOrder order = orderRepository.findByOrderNo(req.orderNo())
                .orElseThrow(() -> new BizException(404, "订单不存在"));

        // 订单必须是本渠道的模拟单，且确实发起过支付
        if (!"MOCK".equals(order.getPayMode())) {
            throw new BizException(403, "该订单不是模拟支付订单，禁止模拟确认");
        }
        if (order.getPayMethod() != method) {
            throw new BizException(403, "支付渠道与订单不一致");
        }
        if (order.getStatus() != OrderStatus.CREATED && order.getStatus() != OrderStatus.PAYING) {
            throw new BizException(409, "订单状态不允许支付：" + order.getStatus());
        }
        if (order.getExpireAt() != null && order.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(409, "订单已超时关闭，请重新下单");
        }

        String tradeNo = "MOCK" + method.name() + System.currentTimeMillis();
        paymentService.handlePaid(order.getOrderNo(), tradeNo, order.getAmount(), method);
        return ApiResponse.ok(Map.of("orderNo", order.getOrderNo(), "tradeNo", tradeNo, "status", "PAID"));
    }
}
