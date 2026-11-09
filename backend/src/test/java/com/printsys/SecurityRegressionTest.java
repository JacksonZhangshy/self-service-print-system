package com.printsys;

import com.printsys.entity.StoredFile;
import com.printsys.entity.User;
import com.printsys.repo.StoredFileRepository;
import com.printsys.repo.UserRepository;
import com.printsys.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 安全回归：模拟支付后门、鉴权、短信限频、XSS 转义。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class SecurityRegressionTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired StoredFileRepository fileRepository;
    @Autowired OrderService orderService;

    private String newOrderNo() throws Exception {
        User u = new User();
        u.setPhone("1380000" + (1000 + (int) (System.currentTimeMillis() % 8000)));
        u = userRepository.save(u);
        StoredFile sf = new StoredFile();
        sf.setUser(u);
        sf.setOriginalName("a.pdf");
        sf.setStoragePath(Files.createTempFile("t", ".pdf").toString());
        sf.setPrintPath(sf.getStoragePath());
        sf.setSizeBytes(10);
        sf.setExtension("pdf");
        sf.setPageCount(1);
        sf = fileRepository.save(sf);
        return orderService.createOrder(u, sf.getId(), 1, "BW", "SINGLE", "A4", null).getOrderNo();
    }

    /** 核心回归：从未发起支付的订单，不能被公开接口标记为已支付（原实现可直接免费打印） */
    @Test
    void mockConfirmRejectedForUnpaidOrder() throws Exception {
        String orderNo = newOrderNo();
        mvc.perform(post("/api/pay/mock/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"" + orderNo + "\",\"method\":\"WECHAT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminApiRequiresAuth() throws Exception {
        mvc.perform(get("/api/admin/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void smsResendIsRateLimited() throws Exception {
        String phone = "13700009999";
        mvc.perform(post("/api/auth/send-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/send-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void mockPayPageEscapesXss() throws Exception {
        String html = mvc.perform(get("/mock-pay")
                        .param("orderNo", "<img src=x onerror=alert(1)>")
                        .param("method", "ALIPAY"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("<img src=x"),
                "订单号必须转义，不能原样输出到 HTML");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("&lt;img"));
    }
}
