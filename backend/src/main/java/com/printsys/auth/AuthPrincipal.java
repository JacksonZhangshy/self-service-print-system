package com.printsys.auth;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 当前请求身份：用户（手机号登录）或管理员（账号登录）。 */
@Getter
@AllArgsConstructor
public class AuthPrincipal {
    /** 普通用户为 sys_user.id，管理员为 admin_user.id */
    private final Long subjectId;
    /** 用户手机号 / 管理员用户名 */
    private final String name;
    private final boolean admin;
}
