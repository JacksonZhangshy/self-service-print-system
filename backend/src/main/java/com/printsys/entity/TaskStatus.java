package com.printsys.entity;

/** 打印任务状态：PENDING 待领取 / PRINTING 打印中 / SUCCESS 成功 / FAILED 失败 / CANCELLED 已取消 */
public enum TaskStatus {
    PENDING,
    PRINTING,
    SUCCESS,
    FAILED,
    CANCELLED
}
