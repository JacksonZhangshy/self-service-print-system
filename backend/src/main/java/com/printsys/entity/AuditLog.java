package com.printsys.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 管理端操作审计：改价、退款、补打、封禁等敏感操作留痕 */
@Getter
@Setter
@Entity
@Table(name = "audit_log", indexes = {@Index(name = "idx_audit_created", columnList = "created_at")})
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String actor;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 64)
    private String target;

    @Column(length = 1000)
    private String detail;

    @Column(length = 45)
    private String ip;

    private LocalDateTime createdAt = LocalDateTime.now();
}
