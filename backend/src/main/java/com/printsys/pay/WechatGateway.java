package com.printsys.pay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.printsys.config.PrintSysProperties;
import com.printsys.entity.PayMethod;
import com.printsys.entity.PrintOrder;
import com.printsys.util.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 微信支付 Native（APIv3）网关。
 * 真实模式能力：Native 下单、订单查询、关单、退款、通知验签（按 Wechatpay-Serial 选证书）+ AES-GCM 解密。
 * 平台证书支持配置多个（轮换期间新旧证书并存），避免证书更新后通知全部验签失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatGateway implements PaymentGateway {
    private static final String HOST = "https://api.mch.weixin.qq.com";
    private static final String NATIVE_PATH = "/v3/pay/transactions/native";

    private final PrintSysProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override
    public PayMethod method() {
        return PayMethod.WECHAT;
    }

    @Override
    public boolean isRealMode() {
        return props.getPay().getWechat().isRealConfigured();
    }

    @Override
    public PrecreateResult precreate(PrintOrder order) {
        if (!isRealMode()) {
            log.info("微信支付未配置完整商户参数，订单 {} 使用模拟支付", order.getOrderNo());
            return MockPaySupport.mockResult(props, order, PayMethod.WECHAT);
        }
        PrintSysProperties.Pay.Wechat w = props.getPay().getWechat();
        long totalCents = order.getAmount().movePointRight(2).longValueExact();
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("appid", w.getAppId());
            req.put("mchid", w.getMchId());
            req.put("description", "自助打印-" + order.getOrderNo());
            req.put("out_trade_no", order.getOrderNo());
            req.put("time_expire", order.getExpireAt().toString() + "+08:00");
            req.put("notify_url", props.getPay().getPublicBaseUrl() + "/api/pay/wechat/notify");
            req.put("amount", Map.of("total", totalCents, "currency", "CNY"));
            String body = objectMapper.writeValueAsString(req);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(HOST + NATIVE_PATH))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", authorization("POST", NATIVE_PATH, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new BizException("微信支付下单失败(HTTP " + resp.statusCode() + "): " + brief(resp.body()));
            }
            JsonNode node = objectMapper.readTree(resp.body());
            return new PrecreateResult(node.get("code_url").asText(), "REAL");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("微信支付下单异常: " + e.getMessage());
        }
    }

    @Override
    public Optional<String> queryPaidTradeNo(PrintOrder order) {
        if (!isRealMode()) {
            return Optional.empty();
        }
        String path = "/v3/pay/transactions/out-trade-no/" + order.getOrderNo()
                + "?mchid=" + props.getPay().getWechat().getMchId();
        try {
            HttpResponse<String> resp = send("GET", path, "");
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode node = objectMapper.readTree(resp.body());
            if ("SUCCESS".equals(node.path("trade_state").asText())) {
                return Optional.ofNullable(node.path("transaction_id").asText(null));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("微信订单查询失败 orderNo={}: {}", order.getOrderNo(), e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void close(PrintOrder order) {
        if (!isRealMode()) {
            return;
        }
        String path = "/v3/pay/transactions/out-trade-no/" + order.getOrderNo() + "/close";
        try {
            String body = objectMapper.writeValueAsString(
                    Map.of("mchid", props.getPay().getWechat().getMchId()));
            HttpResponse<String> resp = send("POST", path, body);
            log.info("微信关单 orderNo={} http={} resp={}", order.getOrderNo(), resp.statusCode(), brief(resp.body()));
        } catch (Exception e) {
            log.warn("微信关单失败 orderNo={}: {}", order.getOrderNo(), e.getMessage());
        }
    }

    @Override
    public RefundResult refund(PrintOrder order, BigDecimal amount, String refundNo, String reason) {
        if (!isRealMode()) {
            return RefundResult.ok("MOCK-" + refundNo);
        }
        long refundCents = amount.movePointRight(2).longValueExact();
        long totalCents = order.getAmount().movePointRight(2).longValueExact();
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("out_trade_no", order.getOrderNo());
            req.put("out_refund_no", refundNo);
            req.put("reason", reason == null ? "打印失败退款" : reason.substring(0, Math.min(80, reason.length())));
            req.put("notify_url", props.getPay().getPublicBaseUrl() + "/api/pay/wechat/refund-notify");
            req.put("amount", Map.of("refund", refundCents, "total", totalCents, "currency", "CNY"));
            String body = objectMapper.writeValueAsString(req);
            HttpResponse<String> resp = send("POST", "/v3/refund/domestic/refunds", body);
            if (resp.statusCode() / 100 != 2) {
                log.warn("微信退款失败 orderNo={} http={} body={}", order.getOrderNo(), resp.statusCode(), brief(resp.body()));
                return RefundResult.fail("微信退款失败: " + brief(resp.body()));
            }
            JsonNode node = objectMapper.readTree(resp.body());
            String status = node.path("status").asText();
            String refundId = node.path("refund_id").asText(null);
            if ("SUCCESS".equals(status)) {
                return RefundResult.ok(refundId);
            }
            return RefundResult.accepted(refundId);
        } catch (Exception e) {
            log.error("微信退款异常 orderNo={}", order.getOrderNo(), e);
            return RefundResult.fail(e.getMessage());
        }
    }

    // ---------------- 通知验签与解密 ----------------

    /**
     * 验签：按 Wechatpay-Serial 选择平台证书，用时间戳+随机串+报文体做 SHA256withRSA 验签。
     * 时间戳超过 5 分钟视为重放，直接拒绝。
     */
    public boolean verifyNotify(String timestamp, String nonce, String serial, String signature, String body) {
        if (timestamp == null || nonce == null || signature == null || body == null) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() / 1000 - ts) > 300) {
                log.warn("微信通知时间戳超出容差，视为重放 ts={}", timestamp);
                return false;
            }
            PublicKey key = pickPlatformKey(serial);
            if (key == null) {
                log.warn("未找到匹配的微信平台证书 serial={}，请更新 printsys.pay.wechat.platform-certs", serial);
                return false;
            }
            String message = timestamp + "\n" + nonce + "\n" + body + "\n";
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            log.warn("微信通知验签异常: {}", e.getMessage());
            return false;
        }
    }

    public String decryptResource(String nonce, String associatedData, String ciphertext) throws Exception {
        String apiV3Key = props.getPay().getWechat().getApiV3Key();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(apiV3Key.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        cipher.updateAAD((associatedData == null ? "" : associatedData).getBytes(StandardCharsets.UTF_8));
        byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
        return new String(plain, StandardCharsets.UTF_8);
    }

    private PublicKey pickPlatformKey(String serial) throws Exception {
        List<X509Certificate> certs = platformCerts();
        if (certs.isEmpty()) {
            return null;
        }
        if (serial != null && !serial.isBlank()) {
            for (X509Certificate c : certs) {
                if (c.getSerialNumber().toString(16).equalsIgnoreCase(serial.trim())) {
                    return c.getPublicKey();
                }
            }
        }
        // 单证书部署时序列号可能对不上（配置的是公钥而非证书），退化为尝试全部证书
        return certs.size() == 1 ? certs.get(0).getPublicKey() : null;
    }

    private List<X509Certificate> platformCerts() throws Exception {
        List<X509Certificate> certs = new ArrayList<>();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (String pem : props.getPay().getWechat().platformCertList()) {
            try (ByteArrayInputStream in = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8))) {
                certs.add((X509Certificate) cf.generateCertificate(in));
            } catch (Exception e) {
                log.warn("微信平台证书解析失败（第 {} 个）: {}", certs.size() + 1, e.getMessage());
            }
        }
        return certs;
    }

    // ---------------- 内部工具 ----------------

    private HttpResponse<String> send(String method, String pathWithQuery, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(HOST + pathWithQuery))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Authorization", authorization(method, pathWithQuery, body));
        if ("POST".equals(method)) {
            b.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.GET();
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String authorization(String method, String pathWithQuery, String body) throws Exception {
        PrintSysProperties.Pay.Wechat w = props.getPay().getWechat();
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String message = method + "\n" + pathWithQuery + "\n" + timestamp + "\n" + nonce + "\n" + body + "\n";
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(parsePrivateKey(w.getPrivateKey()));
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        return "WECHATPAY2-SHA256-RSA2048 mchid=\"" + w.getMchId() + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + signature + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + w.getMerchantSerialNo() + "\"";
    }

    static PrivateKey parsePrivateKey(String pem) throws Exception {
        String cleaned = pem.replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(cleaned);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String brief(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
