package com.printsys.auth;

import com.printsys.config.PrintSysProperties;
import com.printsys.entity.UserSession;
import com.printsys.repo.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 服务端会话：
 * - token 随机 48 字节，库里只存 SHA-256，DB 泄露也无法冒用；
 * - 绝对有效期 + 空闲超时双重约束（公共电脑离开即失效）；
 * - 落库，重启不掉线、可主动吊销、多实例共享。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {
    public static final String TYPE_USER = "USER";
    public static final String TYPE_ADMIN = "ADMIN";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOUCH_INTERVAL = Duration.ofSeconds(60);

    private final UserSessionRepository repo;
    private final PrintSysProperties props;

    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Transactional
    public String issue(String subjectType, Long subjectId, String displayName, String clientIp) {
        byte[] buf = new byte[36];
        RANDOM.nextBytes(buf);
        String token = HexFormat.of().formatHex(buf);

        UserSession s = new UserSession();
        s.setTokenHash(sha256(token));
        s.setSubjectType(subjectType);
        s.setSubjectId(subjectId);
        s.setDisplayName(displayName);
        s.setClientIp(clientIp);
        LocalDateTime now = LocalDateTime.now();
        s.setCreatedAt(now);
        s.setLastSeenAt(now);
        s.setExpiresAt(now.plusHours(props.getAuth().getSessionTtlHours()));
        repo.save(s);
        return token;
    }

    /** 解析 token；无效/过期/空闲超时返回 null */
    @Transactional
    public AuthPrincipal resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String hash = sha256(token);
        Optional<UserSession> found = repo.findById(hash);
        if (found.isEmpty()) {
            return null;
        }
        UserSession s = found.get();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime idleDeadline = s.getLastSeenAt().plusMinutes(props.getAuth().getIdleMinutes());
        if (s.getExpiresAt().isBefore(now) || idleDeadline.isBefore(now)) {
            repo.deleteById(hash);
            return null;
        }
        if (Duration.between(s.getLastSeenAt(), now).compareTo(TOUCH_INTERVAL) > 0) {
            repo.touch(hash, now);
        }
        return new AuthPrincipal(s.getSubjectId(), s.getDisplayName(), TYPE_ADMIN.equals(s.getSubjectType()));
    }

    @Transactional
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            repo.deleteById(sha256(token));
        }
    }

    @Transactional
    public void revokeAllOf(String subjectType, Long subjectId) {
        repo.deleteBySubject(subjectType, subjectId);
    }

    /** 每天凌晨清理过期会话 */
    @Scheduled(cron = "0 20 3 * * ?")
    @Transactional
    public void cleanup() {
        int n = repo.deleteExpired(LocalDateTime.now());
        if (n > 0) {
            log.info("清理过期会话 {} 条", n);
        }
    }
}
