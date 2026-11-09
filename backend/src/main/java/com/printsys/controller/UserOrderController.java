package com.printsys.controller;

import com.printsys.auth.AuthInterceptor;
import com.printsys.dto.OrderView;
import com.printsys.dto.Requests;
import com.printsys.entity.*;
import com.printsys.pay.PaymentService;
import com.printsys.pay.PrecreateResult;
import com.printsys.repo.PrintOrderRepository;
import com.printsys.repo.StoredFileRepository;
import com.printsys.repo.UserRepository;
import com.printsys.service.FileService;
import com.printsys.service.OrderService;
import com.printsys.service.PriceService;
import com.printsys.util.ApiResponse;
import com.printsys.util.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserOrderController {
    private static final int MAX_PAGE_SIZE = 50;

    private final FileService fileService;
    private final OrderService orderService;
    private final PriceService priceService;
    private final PaymentService paymentService;
    private final UserRepository userRepository;
    private final StoredFileRepository fileRepository;
    private final PrintOrderRepository orderRepository;

    private User currentUser(HttpServletRequest request) {
        var p = AuthInterceptor.current(request);
        if (p == null || p.isAdmin()) {
            throw new BizException(403, "请使用手机号登录后操作");
        }
        return userRepository.findById(p.getSubjectId())
                .orElseThrow(() -> new BizException(401, "用户不存在"));
    }

    /** 上传待打印文件：服务端转换 + 真实页数统计，返回 fileId 与页数供用户确认 */
    @PostMapping("/files/upload")
    public ApiResponse<Map<String, Object>> upload(HttpServletRequest request,
                                                   @RequestParam("file") MultipartFile file) {
        StoredFile sf = fileService.store(currentUser(request), file);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileId", sf.getId());
        data.put("fileName", sf.getOriginalName());
        data.put("pageCount", sf.getPageCount());
        data.put("pageSource", sf.getPageSource());
        data.put("sizeBytes", sf.getSizeBytes());
        data.put("extension", sf.getExtension());
        data.put("converted", sf.isConverted());
        return ApiResponse.ok(data);
    }

    /** 打印前预览：返回实际送打印机的 PDF（仅本人可看），确保「看到的 = 打印的」 */
    @GetMapping("/files/{fileId}/preview")
    public ResponseEntity<Resource> preview(HttpServletRequest request, @PathVariable Long fileId) {
        User user = currentUser(request);
        StoredFile sf = fileRepository.findById(fileId)
                .orElseThrow(() -> new BizException(404, "文件不存在"));
        if (!sf.getUser().getId().equals(user.getId())) {
            throw new BizException(403, "无权查看他人文件");
        }
        File f = fileService.resolvePreviewFile(sf);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(f.length())
                .body(new FileSystemResource(f));
    }

    @GetMapping("/prices")
    public ApiResponse<PriceConfig> prices() {
        return ApiResponse.ok(priceService.current());
    }

    @PostMapping("/orders")
    public ApiResponse<OrderView> createOrder(HttpServletRequest request,
                                              @Valid @RequestBody Requests.CreateOrderRequest req) {
        PrintOrder order = orderService.createOrder(currentUser(request), req.fileId(), req.copies(),
                req.colorMode(), req.duplex(), req.paperSize(), req.terminalId());
        return ApiResponse.ok(OrderView.of(order));
    }

    @PostMapping("/orders/{orderNo}/pay")
    public ApiResponse<Map<String, Object>> startPay(HttpServletRequest request,
                                                     @PathVariable String orderNo,
                                                     @Valid @RequestBody Requests.StartPayRequest req) {
        User user = currentUser(request);
        PrintOrder order = orderService.getOwnOrder(user, orderNo);
        PayMethod method;
        try {
            method = PayMethod.valueOf(req.payMethod().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("payMethod 仅支持 ALIPAY/WECHAT");
        }
        PrecreateResult result = paymentService.startPayment(order, method);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", orderNo);
        data.put("qrContent", result.qrContent());
        data.put("payMode", result.mode());
        data.put("amount", order.getAmount());
        data.put("expireAt", order.getExpireAt() == null ? null : order.getExpireAt().toString());
        return ApiResponse.ok(data);
    }

    @GetMapping("/orders/{orderNo}/pay-status")
    public ApiResponse<Map<String, Object>> payStatus(HttpServletRequest request,
                                                      @PathVariable String orderNo) {
        User user = currentUser(request);
        PrintOrder order = orderService.getOwnOrder(user, orderNo);
        OrderStatus status = paymentService.refreshPayStatus(order);
        return ApiResponse.ok(Map.of("orderNo", orderNo, "status", status.name()));
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderView> orderDetail(HttpServletRequest request, @PathVariable String orderNo) {
        return ApiResponse.ok(OrderView.of(orderService.getOwnOrder(currentUser(request), orderNo)));
    }

    /** 取消未支付订单，立即释放二维码 */
    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResponse<OrderView> cancel(HttpServletRequest request, @PathVariable String orderNo) {
        return ApiResponse.ok(OrderView.of(orderService.cancel(currentUser(request), orderNo)));
    }

    @GetMapping("/orders/my")
    public ApiResponse<Map<String, Object>> myOrders(HttpServletRequest request,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        User user = currentUser(request);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var result = orderRepository.findByUserIdOrderByCreatedAtDesc(user.getId(),
                PageRequest.of(Math.max(page, 0), safeSize));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotalElements());
        data.put("list", result.getContent().stream().map(OrderView::of).toList());
        return ApiResponse.ok(data);
    }
}
