package com.printsys.service.sms;

import com.printsys.config.PrintSysProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云短信（Dysmsapi 2017-05-25，RPC 风格 HMAC-SHA1 签名，无 SDK 依赖）。
 * 需要配置：access-key-id / access-key-secret / sign-name / template-code（模板变量名必须为 code）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "printsys.sms.provider", havingValue = "aliyun")
public class AliyunSmsSender implements SmsSender {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final PrintSysProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    @Override
    public String name() {
        return "aliyun";
    }

    @Override
    public void sendCode(String phone, String code) {
        PrintSysProperties.Sms.Aliyun cfg = props.getSms().getAliyun();
        if (isBlank(cfg.getAccessKeyId()) || isBlank(cfg.getAccessKeySecret())
                || isBlank(cfg.getSignName()) || isBlank(cfg.getTemplateCode())) {
            throw new SmsSendException("阿里云短信参数未配置完整（access-key-id/secret/sign-name/template-code）");
        }
        Map<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", cfg.getAccessKeyId());
        params.put("Action", "SendSms");
        params.put("Format", "JSON");
        params.put("PhoneNumbers", phone);
        params.put("RegionId", "cn-hangzhou");
        params.put("SignName", cfg.getSignName());
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("TemplateCode", cfg.getTemplateCode());
        params.put("TemplateParam", "{\"code\":\"" + code + "\"}");
        params.put("Timestamp", ZonedDateTime.now(ZoneOffset.UTC).format(TS));
        params.put("Version", "2017-05-25");

        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (canonical.length() > 0) {
                canonical.append('&');
            }
            canonical.append(percentEncode(e.getKey())).append('=').append(percentEncode(e.getValue()));
        }
        String stringToSign = "GET&" + percentEncode("/") + "&" + percentEncode(canonical.toString());
        String signature = Base64.getEncoder().encodeToString(
                hmacSha1(cfg.getAccessKeySecret() + "&", stringToSign));

        String url = cfg.getEndpoint() + "/?Signature=" + percentEncode(signature) + "&" + canonical;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = resp.body();
            if (resp.statusCode() != 200 || !body.contains("\"Code\":\"OK\"")) {
                log.error("阿里云短信发送失败 HTTP {} body={}", resp.statusCode(), body);
                throw new SmsSendException("短信发送失败，请稍后重试");
            }
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            log.error("阿里云短信通道异常", e);
            throw new SmsSendException("短信通道异常，请稍后重试", e);
        }
    }

    private static byte[] hmacSha1(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    /** 阿里云要求的 RFC3986 编码：空格 %20、* %2A、~ 不编码 */
    private static String percentEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
