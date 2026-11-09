package com.printsys.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 打印用户（手机号即账号）。status: ACTIVE / BLOCKED（后台可封禁异常用户） */
@Getter
@Setter
@Entity
@Table(name = "sys_user", uniqueConstraints = @UniqueConstraint(columnNames = "phone"))
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(length = 50)
    private String nickname;

    @Column(nullable = false, length = 20)
    private String role = "USER";

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime lastLoginAt;
}
