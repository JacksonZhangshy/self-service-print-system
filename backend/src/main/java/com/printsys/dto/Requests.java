package com.printsys.dto;

import jakarta.validation.constraints.*;

public final class Requests {
    private Requests() {
    }

    public record SendCodeRequest(
            @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone) {
    }

    public record LoginRequest(
            @NotBlank @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确") String phone,
            @NotBlank String code) {
    }

    public record AdminLoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record CreateOrderRequest(
            @NotNull Long fileId,
            @Min(1) @Max(100) int copies,
            @NotBlank String colorMode,
            @NotBlank String duplex,
            @NotBlank String paperSize,
            /** 下单终端标识（公共电脑），可为空 */
            String terminalId) {
    }

    public record StartPayRequest(@NotBlank String payMethod) {
    }

    public record MockConfirmRequest(@NotBlank String orderNo, @NotBlank String method) {
    }

    public record ClaimRequest(@NotBlank String agentId, String terminalId) {
    }

    public record TaskReportRequest(
            @NotBlank String agentId,
            @NotBlank String status,
            String failReason,
            @Min(0) Integer actualPages) {
    }

    public record RefundRequest(@NotBlank @Size(max = 180) String reason) {
    }

    public record ReprintRequest(@Size(max = 180) String reason) {
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    public record UpdateUserStatusRequest(@NotBlank String status) {
    }
}
