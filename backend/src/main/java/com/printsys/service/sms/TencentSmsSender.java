package com.printsys.service.sms;

import com.printsys.config.PrintSysProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 腾讯云短信（SendSms 2021-01-11，TC3-HMAC-SHA256 签名，无 SDK 依赖）。
 * 模板变量按顺序对应 TemplateParamSet，第一个变量为验证码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "printsys.sms.provider", havingValue = "tencent")
public class TencentSmsSender implements SmsSender {
    private static final String HOST = "sms.tencentcloudapi.com";
    private static final String SERVICE = "sms";
    private static final String ACTION = "SendSms";
    private static final String VERSION = "2021-01-11";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private final PrintSysProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    @Override
    public String name() {
        return "tencent";
    }

    @Override
    public void sendCode(String phone, String code) {
        PrintSysProperties.Sms.Tencent cfg = props.getSms().getTencent();
        if (isBlank(cfg.getSecretId()) || isBlank(cfg.getSecretKey()) || isBlank(cfg.getSdkAppId())
                || isBlank(cfg.getSignName()) || isBlank(cfg.getTemplateId())) {
            throw new SmsSendException("腾讯云短信参数未配置完整");
        }
        String payload = "{\"PhoneNumberSet\":[\"+86" + phone + "\"],"
                + "\"SmsSdkAppId\":\"" + cfg.getSdkAppId() + "\","
                + "\"SignName\":\"" + cfg.getSignName() + "\","
                + "\"TemplateId\":\"" + cfg.getTemplateId() + "\","
                + "\"TemplateParamSet\":[\"" + code + "\"]}";

        long timestamp = Instant.now().getEpochSecond();
        String date = DATE.format(Instant.ofEpochSecond(timestamp));

        String canonicalRequest = "POST\n/\n\n"
                + "content-type:application/json; charset=utf-8\n"
                + "host:" + HOST + "\n\n"
                + "content-type;host\n"
                + sha256Hex(payload);
        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest);
        byte[] secretDate = hmac(("TC3" + cfg.getSecretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, SERVICE);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));

        String authorization = "TC3-HMAC-SHA256 Credential=" + cfg.getSecretId() + "/" + credentialScope
                + ", SignedHeaders=content-type;host, Signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://" + HOST))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", authorization)
                .header("X-TC-Action", ACTION)
                .header("X-TC-Version", VERSION)
                .header("X-TC-Timestamp", String.valueOf(timestamp))
                .header("X-TC-Region", cfg.getRegion())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || !resp.body().contains("\"Code\":\"Ok\"")) {
                log.error("腾讯云短信发送失败 HTTP {} body={}", resp.statusCode(), resp.body());
                throw new SmsSendException("短信发送失败，请稍后重试");
            }
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            log.error("腾讯云短信通道异常", e);
            throw new SmsSendException("短信通道异常，请稍后重试", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String sha256Hex(String data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
