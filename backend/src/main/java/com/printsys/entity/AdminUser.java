package com.printsys.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 管理员账号（BCrypt 口令，初始化自配置项，可在管理端改密） */
@Getter
@Setter
@Entity
@Table(name = "admin_user", uniqueConstraints = @UniqueConstraint(columnNames = "username"))
public class AdminUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled = true;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime lastLoginAt;
}
