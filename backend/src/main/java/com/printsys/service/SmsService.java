package com.printsys.service;

import com.printsys.auth.SessionService;
import com.printsys.config.PrintSysProperties;
import com.printsys.entity.SmsCode;
import com.printsys.repo.SmsCodeRepository;
import com.printsys.service.sms.SmsSendException;
import com.printsys.service.sms.SmsSender;
import com.printsys.util.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 短信验证码：生成 / 限频 / 校验。
 * 防护：同号 60 秒重发间隔、单号每小时上限、单 IP 每小时上限、单个验证码最多尝试 5 次、5 分钟过期。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsService {
    private static final String PURPOSE_LOGIN = "LOGIN";

    private final SmsCodeRepository repo;
    private final SmsSender sender;
    private final PrintSysProperties props;

    public record SendResult(boolean demoMode, String demoCode, int cooldownSeconds) {
    }

    @Transactional
    public SendResult sendCode(String phone, String clientIp) {
        PrintSysProperties.Sms cfg = props.getSms();
        LocalDateTime now = LocalDateTime.now();

        repo.findFirstByPhoneAndPurposeOrderByCreatedAtDesc(phone, PURPOSE_LOGIN).ifPresent(last -> {
            long passed = Duration.between(last.getCreatedAt(), now).getSeconds();
            if (passed < cfg.getResendSeconds()) {
                throw new BizException(429, "请 " + (cfg.getResendSeconds() - passed) + " 秒后再获取验证码");
            }
        });
        if (repo.countByPhoneAndCreatedAtAfter(phone, now.minusHours(1)) >= cfg.getMaxPerHourPerPhone()) {
            throw new BizException(429, "该手机号获取验证码过于频繁，请稍后再试");
        }
        if (clientIp != null
                && repo.countByClientIpAndCreatedAtAfter(clientIp, now.minusHours(1)) >= cfg.getMaxPerHourPerIp()) {
            throw new BizException(429, "当前网络获取验证码过于频繁，请稍后再试");
        }

        boolean demo = props.getAuth().isDemoMode();
        String code = demo ? props.getAuth().getDemoCode()
                : String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));

        try {
            sender.sendCode(phone, code);
        } catch (SmsSendException e) {
            throw new BizException(502, e.getMessage());
        }

        SmsCode entity = new SmsCode();
        entity.setPhone(phone);
        entity.setPurpose(PURPOSE_LOGIN);
        entity.setCodeHash(hash(phone, code));
        entity.setExpiresAt(now.plusMinutes(cfg.getCodeTtlMinutes()));
        entity.setClientIp(clientIp);
        repo.save(entity);

        return new SendResult(demo, demo ? code : "", cfg.getResendSeconds());
    }

    /** 校验并消费验证码；失败抛 BizException */
    @Transactional
    public void verify(String phone, String code) {
        SmsCode latest = repo.findFirstByPhoneAndPurposeOrderByCreatedAtDesc(phone, PURPOSE_LOGIN)
                .orElseThrow(() -> new BizException("请先获取验证码"));
        if (latest.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException("验证码已过期，请重新获取");
        }
        int maxAttempts = props.getSms().getMaxVerifyAttempts();
        if (latest.getAttempts() >= maxAttempts) {
            throw new BizException("验证码尝试次数过多，请重新获取");
        }
        if (!latest.getCodeHash().equals(hash(phone, code))) {
            latest.setAttempts(latest.getAttempts() + 1);
            repo.save(latest);
            int left = maxAttempts - latest.getAttempts();
            throw new BizException("验证码不正确" + (left > 0 ? "，还可尝试 " + left + " 次" : "，请重新获取"));
        }
        repo.delete(latest);
    }

    private String hash(String phone, String code) {
        return SessionService.sha256(phone + ":" + code + ":" + props.getSms().getCodeSalt());
    }
}
