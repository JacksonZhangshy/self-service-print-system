package com.printsys.controller;

import com.printsys.auth.AuthInterceptor;
import com.printsys.auth.SessionService;
import com.printsys.dto.OrderView;
import com.printsys.dto.Requests;
import com.printsys.dto.TaskView;
import com.printsys.entity.*;
import com.printsys.repo.*;
import com.printsys.service.*;
import com.printsys.util.ApiResponse;
import com.printsys.util.BizException;
import com.printsys.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理端：登录、价格、订单、任务、退款/补打、用户、审计、统计。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {
    private static final int MAX_PAGE_SIZE = 100;

    private final AuthService authService;
    private final PriceService priceService;
    private final StatsService statsService;
    private final RefundService refundService;
    private final PrintTaskService printTaskService;
    private final AuditService auditService;
    private final SessionService sessionService;
    private final PrintOrderRepository orderRepository;
    private final PrintTaskRepository taskRepository;
    private final RefundRecordRepository refundRepository;
    private final AuditLogRepository auditRepository;
    private final UserRepository userRepository;

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody Requests.AdminLoginRequest req,
                                                  HttpServletRequest request) {
        String token = authService.loginAdmin(req.username(), req.password(), ClientIp.of(request));
        return ApiResponse.ok(Map.of("token", token, "username", req.username()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.logout(AuthInterceptor.bearer(request));
        return ApiResponse.ok();
    }

    @PostMapping("/password")
    public ApiResponse<Void> changePassword(HttpServletRequest request,
                                            @Valid @RequestBody Requests.ChangePasswordRequest req) {
        authService.changeAdminPassword(AuthInterceptor.current(request), req.oldPassword(), req.newPassword());
        auditService.log(AuthInterceptor.current(request).getName(), "CHANGE_PASSWORD", "-", "管理员修改口令",
                ClientIp.of(request));
        return ApiResponse.ok();
    }

    @GetMapping("/prices")
    public ApiResponse<PriceConfig> getPrices() {
        return ApiResponse.ok(priceService.current());
    }

    @PutMapping("/prices")
    public ApiResponse<PriceConfig> updatePrices(HttpServletRequest request,
                                                 @RequestBody PriceConfig incoming) {
        PriceConfig saved = priceService.update(incoming);
        auditService.log(AuthInterceptor.current(request).getName(), "UPDATE_PRICE", "price_config",
                "黑白A4=" + saved.getBwA4() + " 彩色A4=" + saved.getColorA4()
                        + " 双面系数=" + saved.getDuplexDiscount(), ClientIp.of(request));
        return ApiResponse.ok(saved);
    }

    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> orders(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String phone,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        var pageable = pageRequest(page, size);
        var result = (phone != null && !phone.isBlank())
                ? orderRepository.findByUserPhoneContainingOrderByCreatedAtDesc(phone.trim(), pageable)
                : (status == null || status.isBlank())
                ? orderRepository.findAllByOrderByCreatedAtDesc(pageable)
                : orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.valueOf(status), pageable);
        return ApiResponse.ok(pageOf(result));
    }

    @GetMapping("/tasks")
    public ApiResponse<Map<String, Object>> tasks(@RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        var pageable = pageRequest(page, size);
        var result = (status == null || status.isBlank())
                ? taskRepository.findAllByOrderByCreatedAtDesc(pageable)
                : taskRepository.findByStatusOrderByCreatedAtDesc(TaskStatus.valueOf(status), pageable);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotalElements());
        data.put("list", result.getContent().stream().map(TaskView::of).toList());
        return ApiResponse.ok(data);
    }

    /** 退款（整单原路退回） */
    @PostMapping("/orders/{orderNo}/refund")
    public ApiResponse<Map<String, Object>> refund(HttpServletRequest request,
                                                   @PathVariable String orderNo,
                                                   @Valid @RequestBody Requests.RefundRequest req) {
        String operator = AuthInterceptor.current(request).getName();
        RefundRecord rec = refundService.refund(orderNo, req.reason(), operator);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", rec.getOrderNo());
        data.put("amount", rec.getAmount());
        data.put("status", rec.getStatus());
        data.put("channelRefundNo", rec.getChannelRefundNo());
        data.put("errorMsg", rec.getErrorMsg());
        return ApiResponse.ok(data);
    }

    /** 补打：为已支付订单重新生成打印任务 */
    @PostMapping("/orders/{orderNo}/reprint")
    public ApiResponse<TaskView> reprint(HttpServletRequest request,
                                         @PathVariable String orderNo,
                                         @RequestBody(required = false) Requests.ReprintRequest req) {
        String operator = AuthInterceptor.current(request).getName();
        PrintTask task = printTaskService.reprint(orderNo, operator);
        auditService.log(operator, "REPRINT", orderNo,
                req == null ? "" : req.reason(), ClientIp.of(request));
        return ApiResponse.ok(TaskView.of(task));
    }

    @GetMapping("/orders/{orderNo}/refunds")
    public ApiResponse<List<RefundRecord>> refundsOf(@PathVariable String orderNo) {
        return ApiResponse.ok(refundRepository.findByOrderNo(orderNo));
    }

    @GetMapping("/refunds")
    public ApiResponse<Map<String, Object>> refunds(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        var result = refundRepository.findAllByOrderByCreatedAtDesc(pageRequest(page, size));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotalElements());
        data.put("list", result.getContent());
        return ApiResponse.ok(data);
    }

    // ---------------- 用户管理 ----------------

    @GetMapping("/users")
    public ApiResponse<Map<String, Object>> users(@RequestParam(required = false) String phone,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        var pageable = PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = (phone == null || phone.isBlank())
                ? userRepository.findAll(pageable)
                : userRepository.findByPhoneContaining(phone.trim(), pageable);
        List<Map<String, Object>> list = result.getContent().stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("phone", u.getPhone());
                    m.put("status", u.getStatus());
                    m.put("createdAt", u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
                    m.put("lastLoginAt", u.getLastLoginAt() == null ? null : u.getLastLoginAt().toString());
                    return m;
                }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotalElements());
        data.put("list", list);
        return ApiResponse.ok(data);
    }

    /** 封禁/解封用户：封禁时立即吊销其全部会话 */
    @PostMapping("/users/{userId}/status")
    public ApiResponse<Void> updateUserStatus(HttpServletRequest request, @PathVariable Long userId,
                                              @Valid @RequestBody Requests.UpdateUserStatusRequest req) {
        String status = req.status().toUpperCase();
        if (!"ACTIVE".equals(status) && !"BLOCKED".equals(status)) {
            throw new BizException("status 仅支持 ACTIVE/BLOCKED");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new BizException(404, "用户不存在"));
        user.setStatus(status);
        userRepository.save(user);
        if ("BLOCKED".equals(status)) {
            sessionService.revokeAllOf(SessionService.TYPE_USER, userId);
        }
        auditService.log(AuthInterceptor.current(request).getName(), "USER_STATUS", user.getPhone(),
                "改为 " + status, ClientIp.of(request));
        return ApiResponse.ok();
    }

    // ---------------- 审计与统计 ----------------

    @GetMapping("/audit")
    public ApiResponse<Map<String, Object>> audit(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        var result = auditRepository.findAllByOrderByCreatedAtDesc(pageRequest(page, size));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotalElements());
        data.put("list", result.getContent());
        return ApiResponse.ok(data);
    }

    @GetMapping("/stats/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(statsService.summary());
    }

    @GetMapping("/stats/daily")
    public ApiResponse<Object> daily(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(statsService.daily(days));
    }

    @GetMapping("/stats/monthly")
    public ApiResponse<Object> monthly(@RequestParam(defaultValue = "12") int months) {
        return ApiResponse.ok(statsService.monthly(months));
    }

    private PageRequest pageRequest(int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageRequest.of(Math.max(page, 0), safeSize);
    }

    private Map<String, Object> pageOf(org.springframework.data.domain.Page<PrintOrder> result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotalElements());
        data.put("list", result.getContent().stream().map(OrderView::of).toList());
        return data;
    }
}
