package com.printsys.dto;

import com.printsys.entity.PrintTask;

/** 打印任务视图（打印助手与管理端共用） */
public record TaskView(
        Long taskId,
        String orderNo,
        String fileName,
        Long fileId,
        int copies,
        String colorMode,
        String duplex,
        String paperSize,
        int pageCount,
        String status,
        String agentId,
        String terminalId,
        int retryCount,
        Integer actualPages,
        String failReason,
        String createdAt,
        String claimedAt,
        String deadlineAt,
        String finishedAt) {

    public static TaskView of(PrintTask t) {
        var order = t.getOrder();
        var file = order == null ? null : order.getFile();
        return new TaskView(
                t.getId(),
                order == null ? null : order.getOrderNo(),
                file == null ? null : file.getOriginalName(),
                file == null ? null : file.getId(),
                order == null ? 0 : order.getCopies(),
                order == null ? null : order.getColorMode(),
                order == null ? null : order.getDuplex(),
                order == null ? null : order.getPaperSize(),
                order == null ? 0 : order.getPageCount(),
                t.getStatus() == null ? null : t.getStatus().name(),
                t.getAgentId(),
                t.getTerminalId(),
                t.getRetryCount(),
                t.getActualPages(),
                t.getFailReason(),
                str(t.getCreatedAt()),
                str(t.getClaimedAt()),
                str(t.getDeadlineAt()),
                str(t.getFinishedAt()));
    }

    private static String str(java.time.LocalDateTime t) {
        return t == null ? null : t.toString();
    }
}
