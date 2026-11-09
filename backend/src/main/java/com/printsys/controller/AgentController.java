package com.printsys.controller;

import com.printsys.dto.Requests;
import com.printsys.dto.TaskView;
import com.printsys.entity.PrintTask;
import com.printsys.entity.StoredFile;
import com.printsys.entity.TaskStatus;
import com.printsys.repo.PrintTaskRepository;
import com.printsys.service.FileService;
import com.printsys.service.PrintTaskService;
import com.printsys.util.ApiResponse;
import com.printsys.util.BizException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 打印助手专用接口（X-Agent-Token 鉴权）。 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {
    private final PrintTaskService printTaskService;
    private final PrintTaskRepository taskRepository;
    private final FileService fileService;

    /** 领取一条待打印任务（按终端过滤）。无任务时 data=null */
    @PostMapping("/tasks/claim")
    public ApiResponse<TaskView> claim(@Valid @RequestBody Requests.ClaimRequest req) {
        PrintTask task = printTaskService.claimNext(req.agentId(), blankToNull(req.terminalId()));
        return ApiResponse.ok(task == null ? null : TaskView.of(task));
    }

    /** 回传打印结果（含实际出纸张数，用于对账） */
    @PostMapping("/tasks/{taskId}/status")
    public ApiResponse<TaskView> report(@PathVariable Long taskId,
                                        @Valid @RequestBody Requests.TaskReportRequest req) {
        PrintTask task = printTaskService.report(taskId, req.agentId(), req.status(),
                req.failReason(), req.actualPages());
        return ApiResponse.ok(TaskView.of(task));
    }

    /**
     * 下载任务文件：只能下载「自己正在打印的那条任务」的文件，
     * 不再暴露 /files/{fileId} 这种可枚举的入口。
     */
    @GetMapping("/tasks/{taskId}/file")
    public ResponseEntity<Resource> download(@PathVariable Long taskId,
                                             @RequestParam String agentId) {
        // 必须 fetch join：控制器不在事务内，懒加载 order/file 会抛 LazyInitializationException
        PrintTask task = taskRepository.findByIdFetch(taskId)
                .orElseThrow(() -> new BizException(404, "打印任务不存在"));
        if (task.getStatus() != TaskStatus.PRINTING) {
            throw new BizException(409, "任务不在打印中状态");
        }
        if (task.getAgentId() != null && !task.getAgentId().equals(agentId)) {
            throw new BizException(403, "该任务不属于当前打印助手");
        }
        StoredFile sf = task.getOrder().getFile();
        File f = fileService.resolvePrintFile(sf);
        String name = sf.getOriginalName() == null ? "task.pdf" : sf.getOriginalName();
        int dot = name.lastIndexOf('.');
        String pdfName = (dot > 0 ? name.substring(0, dot) : name) + ".pdf";
        String encoded = URLEncoder.encode(pdfName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(f.length())
                .body(new FileSystemResource(f));
    }

    @GetMapping("/ping")
    public ApiResponse<Map<String, String>> ping() {
        return ApiResponse.ok(Map.of("status", "ok"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
