package com.printsys.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 短信验证码（只存散列，5 分钟有效，限制尝试次数与重发频率） */
@Getter
@Setter
@Entity
@Table(name = "sms_code", indexes = {
        @Index(name = "idx_sms_phone", columnList = "phone"),
        @Index(name = "idx_sms_ip", columnList = "client_ip,created_at")
})
public class SmsCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phone;

    /** SHA-256(phone + ":" + code + ":" + salt) */
    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false, length = 16)
    private String purpose = "LOGIN";

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
