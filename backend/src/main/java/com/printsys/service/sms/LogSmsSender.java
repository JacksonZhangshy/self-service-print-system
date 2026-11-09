package com.printsys.service.sms;

import com.printsys.config.PrintSysProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 日志通道：本地开发/验收演示用，验证码只写入服务端日志，不真实下发。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "printsys.sms.provider", havingValue = "log", matchIfMissing = true)
public class LogSmsSender implements SmsSender {
    private final PrintSysProperties props;

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void sendCode(String phone, String code) {
        log.info("[SMS-LOG] 手机号 {} 的登录验证码：{}（未真实下发，生产请配置 printsys.sms.provider=aliyun/tencent/webhook）",
                phone, code);
    }
}
