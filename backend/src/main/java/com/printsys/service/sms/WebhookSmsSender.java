package com.printsys.service.sms;

import com.printsys.config.PrintSysProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 通用 Webhook 通道：POST {phone, code} 到自建网关（企业已有短信平台时最省事）。
 * 约定返回 HTTP 2xx 视为成功。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "printsys.sms.provider", havingValue = "webhook")
public class WebhookSmsSender implements SmsSender {
    private final PrintSysProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public void sendCode(String phone, String code) {
        PrintSysProperties.Sms.Webhook cfg = props.getSms().getWebhook();
        if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            throw new SmsSendException("短信 Webhook 地址未配置");
        }
        String body = "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\",\"purpose\":\"LOGIN\"}";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(cfg.getUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (cfg.getToken() != null && !cfg.getToken().isBlank()) {
            builder.header("Authorization", "Bearer " + cfg.getToken());
        }
        try {
            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.error("Webhook 短信发送失败 HTTP {} body={}", resp.statusCode(), resp.body());
                throw new SmsSendException("短信发送失败，请稍后重试");
            }
        } catch (SmsSendException e) {
            throw e;
        } catch (Exception e) {
            log.error("Webhook 短信通道异常", e);
            throw new SmsSendException("短信通道异常，请稍后重试", e);
        }
    }
}
