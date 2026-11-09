#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
自助收费打印系统 · 端到端冒烟 + 攻击复测脚本（仅用标准库，无第三方依赖）

前置：后端以 demo profile 启动
    cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=demo

用法：
    python3 scripts/smoke_test.py [后端地址]
    python3 scripts/smoke_test.py http://localhost:8080

覆盖范围：
  A 健康检查 / 未授权拦截
  B 登录与文件上传（含伪装文件拦截）
  C 计价与下单（含终端绑定）
  D 支付安全复测：未支付直接入账 / 渠道不匹配 / 重复入账 / 越权查他人订单
  E 打印任务：终端过滤 / 并发唯一领取 / 越权下载 / 结果回传
  F 管理端：鉴权、统计、退款、退款后禁止补打、审计留痕
  G 订单生命周期：取消、取消后不可支付
  H 模拟支付页 XSS 转义

全部通过打印 SMOKE TEST PASSED；有失败项则打印清单并以非 0 退出。
"""
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
AGENT_TOKEN = "agent-dev-token"      # 与 demo profile 的 printsys.agent.token 一致
PHONE_A = "1370000" + str(uuid.uuid4().int)[:4]
PHONE_B = "1380000" + str(uuid.uuid4().int)[:4]
AGENT_1 = "smoke-agent-01"
AGENT_2 = "smoke-agent-02"
TERMINAL = "T01"

results = []   # (ok, name, detail)


def check(name, cond, detail=""):
    results.append((bool(cond), name, "" if cond else str(detail)[:300]))
    flag = "PASS" if cond else "FAIL"
    print(f"  [{flag}] {name}" + ("" if cond else f"  ← {str(detail)[:300]}"))
    return bool(cond)


def section(title):
    print(f"\n== {title} ==")


def call(method, path, body=None, token=None, agent=False, raw=False, timeout=30):
    url = BASE + path
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    if agent:
        headers["X-Agent-Token"] = AGENT_TOKEN
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = resp.read()
            if raw:
                return resp.status, payload
            return json.loads(payload.decode("utf-8"))
    except urllib.error.HTTPError as e:
        payload = e.read()
        if raw:
            return e.code, payload
        try:
            return json.loads(payload.decode("utf-8"))
        except Exception:
            return {"code": -1, "msg": payload.decode("utf-8", "replace")}
    except Exception as e:  # 连接失败等
        if raw:
            return 0, b""
        return {"code": -1, "msg": f"请求异常：{e}"}


def ok(r):
    return isinstance(r, dict) and r.get("code") == 0


def make_pdf(pages=1):
    """手工拼一个合法 PDF（无第三方依赖），用于验证真实页数解析。"""
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>"]
    kids = b" ".join(b"3 0 R" for _ in range(pages))
    objects.append(b"<< /Type /Pages /Kids [" + kids + b"] /Count " + str(pages).encode() + b" >>")
    for _ in range(pages):
        objects.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>")
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, obj in enumerate(objects, start=1):
        offsets.append(len(out))
        out += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n".encode() + b"0000000000 65535 f \n"
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += (f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
            f"startxref\n{xref}\n%%EOF").encode()
    return bytes(out)


def upload(token, content, filename="smoke.pdf", mime="application/pdf"):
    boundary = "----smoke" + uuid.uuid4().hex
    body = bytearray()
    body += f"--{boundary}\r\n".encode()
    body += f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode()
    body += f"Content-Type: {mime}\r\n\r\n".encode()
    body += content + b"\r\n"
    body += f"--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        BASE + "/api/files/upload", data=bytes(body), method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                 "Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        payload = e.read().decode("utf-8", "replace")
        try:
            return json.loads(payload)
        except Exception:
            return {"code": -1, "msg": payload}


def login(phone):
    r = call("POST", "/api/auth/send-code", {"phone": phone})
    code = ((r.get("data") or {}).get("demoCode")) or "123456"
    r = call("POST", "/api/auth/login", {"phone": phone, "code": code})
    return (r.get("data") or {}).get("token")


def create_order(token, file_id, copies=2, color="BW", duplex="SINGLE", paper="A4", terminal=TERMINAL):
    return call("POST", "/api/orders", {
        "fileId": file_id, "copies": copies, "colorMode": color,
        "duplex": duplex, "paperSize": paper, "terminalId": terminal
    }, token=token)


def amount_of(order):
    try:
        return round(float(order.get("amount")), 2)
    except (TypeError, ValueError):
        return -1.0


def main():
    print(f"目标后端：{BASE}")
    print(f"测试手机号：{PHONE_A} / {PHONE_B}")

    # ---------------- A 基础 ----------------
    section("A 健康检查与未授权拦截")
    r = call("GET", "/api/health")
    check("健康检查返回 ok", ok(r), r)
    r = call("GET", "/api/orders/my")
    check("未登录访问订单接口被拦截（401）", not ok(r) and r.get("code") in (401, 403, -1) or r.get("code") != 0, r)
    r = call("GET", "/api/admin/orders")
    check("未登录访问管理端被拦截", not ok(r), r)

    # ---------------- B 登录与上传 ----------------
    section("B 登录与文件上传")
    token_a = login(PHONE_A)
    check("手机号登录成功", bool(token_a), "token 为空")
    r = call("GET", "/api/auth/me", token=token_a)
    me = r.get("data") or {}
    check("/auth/me 返回当前登录用户", ok(r) and me.get("name") == PHONE_A and me.get("admin") is False, r)

    r = upload(token_a, make_pdf(3))
    check("上传 3 页 PDF 并解析页数=3", ok(r) and (r.get("data") or {}).get("pageCount") == 3, r)
    file_id = (r.get("data") or {}).get("fileId")

    r = upload(token_a, b"this is not a real pdf at all", filename="fake.pdf")
    check("伪装文件被魔数校验拦截", not ok(r), r)

    # ---------------- C 计价与下单 ----------------
    section("C 计价与下单")
    r = create_order(token_a, file_id, copies=2)
    check("创建订单成功", ok(r), r)
    order1 = r.get("data") or {}
    order_no1 = order1.get("orderNo")
    # 黑白 A4 0.30 × 3 页 × 2 份 = 1.80
    check("计价正确（0.30×3页×2份=1.80）", abs(amount_of(order1) - 1.80) < 0.001, order1.get("amount"))
    check("订单绑定终端 T01", order1.get("terminalId") == TERMINAL, order1.get("terminalId"))
    check("未支付订单带倒计时", isinstance(order1.get("paySecondsLeft"), (int, float)) and order1["paySecondsLeft"] > 0,
          order1.get("paySecondsLeft"))
    check("页数来源标注", order1.get("pageSource") in ("REAL", "ESTIMATE"), order1.get("pageSource"))

    # ---------------- D 支付安全复测 ----------------
    section("D 支付安全复测（攻击路径）")
    r = call("POST", "/api/pay/mock/confirm", {"orderNo": order_no1, "method": "ALIPAY"})
    check("【攻击】未发起支付直接标记已支付 → 被拒", not ok(r), r)
    r = call("GET", f"/api/orders/{order_no1}", token=token_a)
    check("订单仍为 CREATED（未被白打）", (r.get("data") or {}).get("status") == "CREATED", r)

    r = call("POST", f"/api/orders/{order_no1}/pay", {"payMethod": "ALIPAY"}, token=token_a)
    check("发起支付宝支付", ok(r), r)
    pay = r.get("data") or {}
    check("demo 环境走模拟收银台", pay.get("payMode") == "MOCK", pay.get("payMode"))
    check("二维码内容指向模拟支付页", "/mock-pay" in (pay.get("qrContent") or ""), pay.get("qrContent"))

    r = call("POST", "/api/pay/mock/confirm", {"orderNo": order_no1, "method": "WECHAT"})
    check("【攻击】用微信渠道确认支付宝订单 → 被拒", not ok(r), r)

    token_b = login(PHONE_B)
    r = call("GET", f"/api/orders/{order_no1}", token=token_b)
    check("【攻击】他人订单详情越权访问 → 被拒", not ok(r), r)
    r = call("POST", f"/api/orders/{order_no1}/pay", {"payMethod": "ALIPAY"}, token=token_b)
    check("【攻击】他人订单发起支付 → 被拒", not ok(r), r)

    r = call("POST", "/api/pay/mock/confirm", {"orderNo": order_no1, "method": "ALIPAY"})
    check("正确渠道确认支付入账", ok(r) and (r.get("data") or {}).get("status") == "PAID", r)
    r = call("POST", "/api/pay/mock/confirm", {"orderNo": order_no1, "method": "ALIPAY"})
    check("【攻击】重复确认已支付订单 → 被拒", not ok(r), r)

    r = call("GET", f"/api/orders/{order_no1}/pay-status", token=token_a)
    check("支付状态查询 = PAID", (r.get("data") or {}).get("status") == "PAID", r)

    # ---------------- E 打印任务 ----------------
    section("E 打印任务与并发领取")
    r = call("POST", "/api/agent/tasks/claim", {"agentId": AGENT_1, "terminalId": TERMINAL}, agent=True)
    check("助手按终端领取任务成功", ok(r) and (r.get("data") or {}).get("orderNo") == order_no1, r)
    task = r.get("data") or {}
    task_id = task.get("taskId")
    check("任务带截止时间", bool(task.get("deadlineAt")), task)

    r = call("POST", "/api/agent/tasks/claim", {"agentId": AGENT_2, "terminalId": TERMINAL}, agent=True)
    check("【并发】同一任务不会被第二个助手重复领取", ok(r) and r.get("data") is None, r)
    r = call("POST", "/api/agent/tasks/claim", {"agentId": AGENT_1, "terminalId": "T99"}, agent=True)
    check("其他终端领取不到本终端任务", ok(r) and r.get("data") is None, r)

    status, payload = call("GET", f"/api/agent/tasks/{task_id}/file?agentId={AGENT_2}", agent=True, raw=True)
    check("【攻击】用他人 agentId 下载任务文件 → 被拒", status >= 400 or payload[:1] == b"{", f"HTTP {status}")
    status, payload = call("GET", f"/api/agent/tasks/{task_id}/file?agentId={AGENT_1}", agent=True, raw=True)
    check("助手下载任务文件为 PDF", status == 200 and payload[:4] == b"%PDF", f"HTTP {status} {payload[:20]}")

    r = call("POST", f"/api/agent/tasks/{task_id}/status",
             {"agentId": AGENT_1, "status": "SUCCESS", "actualPages": 3}, agent=True)
    check("回传打印成功（含实际页数）", ok(r) and (r.get("data") or {}).get("status") == "SUCCESS", r)
    check("任务记录实际出纸张数", (r.get("data") or {}).get("actualPages") == 3, r)

    r = call("GET", f"/api/orders/{order_no1}", token=token_a)
    check("订单终态 = PRINTED", (r.get("data") or {}).get("status") == "PRINTED", r)
    r = call("POST", f"/api/agent/tasks/{task_id}/status",
             {"agentId": AGENT_2, "status": "SUCCESS"}, agent=True)
    check("【攻击】他人回传任务结果 → 被拒", not ok(r), r)

    # ---------------- F 管理端 ----------------
    section("F 管理端运维能力")
    r = call("POST", "/api/admin/login", {"username": "admin", "password": "admin123"})
    check("管理员登录", ok(r) and (r.get("data") or {}).get("token"), r)
    admin = (r.get("data") or {}).get("token")

    r = call("GET", "/api/admin/stats/summary", token=admin)
    s = r.get("data") or {}
    check("收益统计含今日订单", ok(r) and s.get("todayOrders", 0) >= 1, r)
    check("统计口径含退款与净收入", "todayRefunded" in s and "todayNet" in s, s)

    r = call("GET", "/api/admin/orders?page=0&size=10", token=admin)
    check("管理端订单列表可用", ok(r) and (r.get("data") or {}).get("total", 0) >= 1, r)
    r = call("GET", f"/api/admin/orders?phone={PHONE_A}", token=admin)
    check("按手机号筛选订单", ok(r) and (r.get("data") or {}).get("total", 0) >= 1, r)
    r = call("GET", "/api/admin/tasks?status=SUCCESS", token=admin)
    check("按状态筛选打印任务", ok(r) and (r.get("data") or {}).get("total", 0) >= 1, r)

    r = call("POST", f"/api/admin/orders/{order_no1}/reprint", {"reason": "冒烟测试补打"}, token=admin)
    check("补打生成新任务", ok(r) and (r.get("data") or {}).get("taskId"), r)
    new_task = (r.get("data") or {}).get("taskId")
    if new_task:
        call("POST", f"/api/agent/tasks/claim", {"agentId": AGENT_1, "terminalId": TERMINAL}, agent=True)
        r = call("POST", f"/api/agent/tasks/{new_task}/status",
                 {"agentId": AGENT_1, "status": "SUCCESS", "actualPages": 3}, agent=True)
        check("补打任务可正常完成", ok(r), r)

    r = call("POST", f"/api/admin/orders/{order_no1}/refund", {"reason": "冒烟测试退款"}, token=admin)
    check("管理端整单退款成功", ok(r) and (r.get("data") or {}).get("status") == "SUCCESS", r)
    r = call("GET", f"/api/orders/{order_no1}", token=token_a)
    check("退款后订单状态 = REFUNDED", (r.get("data") or {}).get("status") == "REFUNDED", r)
    r = call("POST", f"/api/admin/orders/{order_no1}/refund", {"reason": "重复退款"}, token=admin)
    check("【攻击】重复退款 → 被拒", not ok(r), r)
    r = call("POST", f"/api/admin/orders/{order_no1}/reprint", {"reason": "退款后补打"}, token=admin)
    check("已退款订单禁止补打", not ok(r), r)

    r = call("GET", "/api/admin/audit?page=0&size=20", token=admin)
    actions = [x.get("action") for x in ((r.get("data") or {}).get("list") or [])]
    check("敏感操作留下审计记录（补打/退款）", "REPRINT" in actions and "REFUND" in actions, actions)

    r = call("GET", "/api/admin/users?page=0&size=20", token=admin)
    users = (r.get("data") or {}).get("list") or []
    check("用户列表可用", ok(r) and len(users) >= 1, r)
    uid = next((u["id"] for u in users if u.get("phone") == PHONE_B), None)
    if uid:
        r = call("POST", f"/api/admin/users/{uid}/status", {"status": "BLOCKED"}, token=admin)
        check("封禁用户成功", ok(r), r)
        r = call("GET", "/api/orders/my", token=token_b)
        check("封禁后该用户会话立即失效", not ok(r), r)
        r = call("POST", f"/api/admin/users/{uid}/status", {"status": "ACTIVE"}, token=admin)
        check("解封用户成功", ok(r), r)

    # ---------------- G 生命周期 ----------------
    section("G 订单生命周期")
    r = create_order(token_a, file_id, copies=1, duplex="DUPLEX")
    order2 = r.get("data") or {}
    order_no2 = order2.get("orderNo")
    # 黑白 A4 0.30 × 3 页 × 1 份 × 双面 0.90 = 0.81
    check("双面订单计价正确（0.81）", abs(amount_of(order2) - 0.81) < 0.001, order2.get("amount"))
    r = call("POST", f"/api/orders/{order_no2}/cancel", token=token_a)
    check("取消未支付订单", ok(r) and (r.get("data") or {}).get("status") == "CANCELLED", r)
    r = call("POST", f"/api/orders/{order_no2}/pay", {"payMethod": "ALIPAY"}, token=token_a)
    check("已取消订单不可再支付", not ok(r), r)

    # ---------------- H XSS ----------------
    section("H 模拟支付页 XSS 转义")
    q = urllib.parse.quote("<script>alert(1)</script>")
    status, payload = call("GET", f"/mock-pay?orderNo={q}&method=ALIPAY", raw=True)
    html = payload.decode("utf-8", "replace")
    check("模拟支付页可访问", status == 200, f"HTTP {status}")
    check("【攻击】订单号中的脚本被转义（不出现原始 <script>）",
          "<script>alert(1)</script>" not in html, html[:200])

    # ---------------- 汇总 ----------------
    failed = [x for x in results if not x[0]]
    total = len(results)
    print("\n" + "=" * 62)
    if failed:
        print(f"SMOKE TEST FAILED：{len(failed)}/{total} 项未通过")
        for _, name, detail in failed:
            print(f"  - {name}  {detail}")
        print("=" * 62)
        return 1
    print(f"SMOKE TEST PASSED（{total} 项全部通过）")
    print("=" * 62)
    return 0


if __name__ == "__main__":
    sys.exit(main())
