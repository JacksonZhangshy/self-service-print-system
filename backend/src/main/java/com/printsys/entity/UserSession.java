package com.printsys.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 服务端会话（落库，可吊销、支持多实例、重启不掉线）。
 * token 本身不放库，只存其 SHA-256，避免数据库泄露即会话泄露。
 */
@Getter
@Setter
@Entity
@Table(name = "user_session", indexes = {
        @Index(name = "idx_session_subject", columnList = "subject_type,subject_id")
})
public class UserSession {
    @Id
    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    /** USER / ADMIN */
    @Column(name = "subject_type", length = 10, nullable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "display_name", length = 64)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "client_ip", length = 45)
    private String clientIp;
}
