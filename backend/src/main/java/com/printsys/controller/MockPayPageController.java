package com.printsys.controller;

import com.printsys.entity.PrintOrder;
import com.printsys.repo.PrintOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 模拟收银台（仅当模拟支付开启时才会被生成二维码指向）。
 * 所有动态内容做 HTML 转义（原实现直接把 orderNo 拼进页面，存在反射型 XSS）；
 * 页面展示真实订单金额，避免「不知道付多少就点确认」。
 */
@Controller
@RequiredArgsConstructor
public class MockPayPageController {
    private final PrintOrderRepository orderRepository;

    @GetMapping(value = "/mock-pay", produces = "text/html;charset=UTF-8")
    @ResponseBody
    public String mockPay(@RequestParam String orderNo, @RequestParam String method) {
        String channel = "ALIPAY".equalsIgnoreCase(method) ? "支付宝" : "微信支付";
        PrintOrder order = orderRepository.findByOrderNo(orderNo).orElse(null);

        String amount = order == null ? "-" : order.getAmount().toPlainString();
        String status = order == null ? "订单不存在" : order.getStatus().name();
        boolean payable = order != null
                && ("CREATED".equals(order.getStatus().name()) || "PAYING".equals(order.getStatus().name()))
                && "MOCK".equals(order.getPayMode())
                && order.getPayMethod() != null
                && order.getPayMethod().name().equalsIgnoreCase(method);

        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN"><head><meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>模拟收银台</title>
                <style>
                  body{font-family:-apple-system,'PingFang SC',sans-serif;background:#f5f6f8;margin:0;padding:24px}
                  .card{max-width:420px;margin:40px auto;background:#fff;border-radius:12px;padding:28px;box-shadow:0 2px 12px rgba(0,0,0,.08)}
                  .tag{display:inline-block;background:#fdf6ec;color:#e6a23c;border:1px solid #faecd8;border-radius:4px;padding:2px 8px;font-size:12px}
                  h2{margin:14px 0 6px}.muted{color:#909399;font-size:13px}
                  .amount{font-size:30px;color:#f56c6c;margin:16px 0 4px}
                  button{width:100%;margin-top:22px;padding:12px;font-size:16px;color:#fff;background:#1677ff;border:none;border-radius:8px;cursor:pointer}
                  button:disabled{background:#a0cfff}.ok{color:#67c23a;font-size:15px;margin-top:16px;display:none}
                  .err{color:#f56c6c;font-size:14px;margin-top:14px}
                </style></head>
                <body><div class="card">
                  <span class="tag">模拟支付模式 · 未接入真实商户参数，不会产生真实扣款</span>
                  <h2>__CHANNEL__（模拟）</h2>
                  <div class="muted">订单号：__ORDER_NO__</div>
                  <div class="muted">当前状态：__STATUS__</div>
                  <div class="amount">¥__AMOUNT__</div>
                  <button id="btn" onclick="pay()" __DISABLED__>确认支付</button>
                  <div class="ok" id="ok">支付成功，请回到打印终端查看打印状态</div>
                  <div class="err" id="err">__HINT__</div>
                </div>
                <script>
                const ORDER_NO = "__ORDER_NO_JS__";
                const METHOD = "__METHOD__";
                async function pay(){
                  const btn=document.getElementById('btn');btn.disabled=true;btn.textContent='处理中…';
                  try{
                    const r=await fetch('/api/pay/mock/confirm',{method:'POST',headers:{'Content-Type':'application/json'},
                      body:JSON.stringify({orderNo:ORDER_NO,method:METHOD})});
                    const j=await r.json();
                    if(j.code===0){document.getElementById('ok').style.display='block';btn.textContent='支付完成';document.getElementById('err').textContent='';}
                    else{btn.disabled=false;btn.textContent='确认支付';document.getElementById('err').textContent=j.msg||'支付失败';}
                  }catch(e){
                    btn.disabled=false;btn.textContent='确认支付';
                    document.getElementById('err').textContent='网络异常，请重试';
                  }
                }
                </script></body></html>
                """;
        return html
                .replace("__CHANNEL__", escape(channel))
                .replace("__ORDER_NO_JS__", escapeJs(orderNo))
                .replace("__ORDER_NO__", escape(orderNo))
                .replace("__STATUS__", escape(status))
                .replace("__AMOUNT__", escape(amount))
                .replace("__METHOD__", escapeJs(method.toUpperCase()))
                .replace("__DISABLED__", payable ? "" : "disabled")
                .replace("__HINT__", payable ? "" : escape("该订单当前不可支付（非本渠道模拟单或已关闭）"));
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escapeJs(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("'", "\\u0027")
                .replace("\n", "");
    }
}
