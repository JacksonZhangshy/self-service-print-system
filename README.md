# 自助收费打印系统 · Self-Service Print System

> 仓库英文名 `self-service-print-system`（GitHub 仓库名仅支持 ASCII，故以英文命名）；Gitee 显示名为中文全称。

面向公共电脑场景的自助打印收费系统：用户在公共电脑上用手机号登录 → 上传文件 → 选打印参数 → 微信/支付宝扫码付款 → 付款成功后由打印电脑上的助手自动领取任务并出纸。管理端可在线改价、查订单与任务、退款/补打、看收益与审计。

- **需求方**：人工智能教研室
- **应用场景**：智慧园区（图书馆 / 自习室 / 服务大厅等公共区域）
- **技术栈**（按招标指定）：前端 **Vue 3** · 后端 **Java 17 + Spring Boot 3** · 打印助手 **.NET 8** · MySQL 8 · Nginx · Git

---

## 1. 这个系统解决的关键问题

自助打印最容易翻车的三件事，本项目都有针对性设计：

| 常见问题 | 本项目的做法 |
|---|---|
| 有人不付钱也能出纸 | 支付回调校验 **签名 + 收款方 + 金额**；模拟支付只在 demo 显式开启，生产默认关闭且没有任何旁路 |
| 多台机器抢同一任务，一张纸打出来三份 | 任务领取用**数据库条件更新**（`WHERE status='PENDING'`），受影响行数为 1 才算领到；8 线程并发测试恰好 1 个成功 |
| 命令"执行成功"但纸没出来，用户被收了钱 | 打印助手提交后**回查系统打印队列**（Windows Spooler `EnumJobs` / CUPS `lpstat`），任务出队才算成功；缺纸/脱机/卡纸判失败并**自动退款** |

另外还补齐了实际运营必需的能力：真实页数计费（LibreOffice 转 PDF 后按页算）、订单超时关单、任务超时回收重试、退款、补打、终端绑定、会话空闲自动登出、短信验证码限流、文件到期清理、管理端审计日志。

---

## 2. 目录结构

```
print-system/
├── backend/                    Java 17 + Spring Boot 3（Maven）
│   ├── pom.xml
│   ├── schema-mysql.sql        MySQL 8 建表脚本（10 张表）
│   └── src/main/java/com/printsys/
│       ├── auth/               会话、鉴权拦截器
│       ├── config/             配置树、Web、密码编码器
│       ├── controller/         用户端 / 助手端 / 管理端 / 支付回调 / 健康检查
│       ├── dto/                出参视图与入参校验
│       ├── entity/             10 个实体 + 4 个状态枚举
│       ├── job/                订单超时、任务回收、文件清理
│       ├── pay/                支付宝 / 微信 / 模拟支付
│       ├── repo/               JPA 仓储（行锁 + 条件更新）
│       └── service/            业务编排（订单、任务、退款、短信、转换、统计…）
├── frontend/                   Vue 3 + Vite + Element Plus
│   └── src/views/{user,admin}/ 用户端 4 页 + 管理端 7 页
├── print-agent/PrintAgent/     .NET 8 打印助手（SumatraPDF / CUPS + 队列回查）
├── deploy/                     Nginx、systemd、Docker、一键安装脚本、Windows 助手安装脚本
├── docs/                       详细设计文档 / 部署与使用说明书 / 测试报告
├── scripts/smoke_test.py       端到端冒烟 + 攻击复测（55 项断言）
├── docker-compose.yml
└── .env.example                环境变量模板（所有敏感项都从环境变量注入）
```

---

## 3. 五分钟跑通（演示模式，不需要 MySQL、不需要商户参数）

```bash
# 1) 后端：H2 内存库 + 验证码固定 123456 + 模拟支付 + 估算页数
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=demo      # http://localhost:8080

# 2) 前端
cd frontend
npm install && npm run dev                               # http://localhost:5173

# 3) 冒烟 + 攻击复测（可选，后端运行中执行）
python3 scripts/smoke_test.py http://localhost:8080
```

演示入口：

| 入口 | 地址 | 账号 |
|---|---|---|
| 用户端 | `http://localhost:5173/user/login` | 任意 11 位手机号 + 验证码 `123456` |
| 管理端 | `http://localhost:5173/admin/login` | `admin` / `admin123` |

终端号：地址带 `?t=T01` 表示"1 号打印终端"，下单会绑定该终端，助手只领自己终端的任务。

---

## 4. 生产部署

两种方式，任选其一（详见 `docs/部署与使用说明书.md`）：

```bash
# 方式一：Docker Compose
cp .env.example .env && vi .env        # 至少改 PUBLIC_BASE_URL / DB_PASSWORD / ADMIN_PASSWORD / AGENT_TOKEN
docker compose up -d --build

# 方式二：Ubuntu 裸机一键脚本（自动装 JDK/LibreOffice/MySQL/Nginx 并配置 systemd）
sudo bash deploy/install.sh
```

上线前**必须**确认的几项：

| 配置 | 值 | 为什么 |
|---|---|---|
| `PAY_MOCK_ENABLED` | `false` | 为 true 时任何人都能把订单标成已支付而不付钱 |
| `ALIPAY_SELLER_ID` / `WECHAT_MCH_ID` | 真实商户号 | 回调校验收款方，防止钱进别人账户 |
| `PUBLIC_BASE_URL` | 公网可达的 HTTPS 域名 | 支付回调靠它，不通就永远"支付中" |
| `AGENT_TOKEN` | 32 位以上随机串 | 打印助手与后端之间的共享密钥 |
| `SMS_PROVIDER` | `aliyun` / `tencent` / `webhook` | 默认 `log` 只打日志，不发短信 |
| `CONVERT_ENABLED` | `true` + 已装 LibreOffice | 否则 Word/Excel 只能按估算页数计价 |

打印电脑上部署助手：

```powershell
# Windows（管理员 PowerShell，在 publish 目录执行）
.\install-agent.ps1 -ApiBaseUrl "http://192.168.1.10:8080" `
                    -AgentToken "<与服务器一致>" `
                    -TerminalId "T01" `
                    -PrinterName "HP LaserJet Pro M404-M405"
```

```bash
# 任何机器上都能先验一遍环境
PrintAgent --check          # 配置 / 后端连通 / 密钥 / 打印通道 / 打印机
PrintAgent --dry-run --once # 领取并下载但不打印，验证整条链路且不浪费纸
```

---

## 5. 验证情况

| 项目 | 结果 |
|---|---|
| 后端自动化测试 | **17/17 通过**（计价 5、支付与任务 7、安全回归 5） |
| 端到端冒烟 + 攻击复测 | **55/55 断言通过**（含白打、越权、重放、重复退款、XSS 等攻击路径） |
| 前端构建 | `npm run build` 零错误，按页面分包（用户端不加载图表库） |
| 打印助手 | `dotnet build -c Release` 0 warning / 0 error；真实进程联调跑到"打印失败→自动退款"闭环 |

细节见 `docs/测试报告.md`。

---

## 6. 主要接口

| 分组 | 前缀 | 鉴权 |
|---|---|---|
| 用户端 | `/api/auth` `/api/files` `/api/orders` `/api/prices` | `Authorization: Bearer <用户 token>` |
| 打印助手 | `/api/agent` | `X-Agent-Token: <共享密钥>` |
| 管理端 | `/api/admin` | `Authorization: Bearer <管理员 token>` |
| 支付回调 | `/api/pay/notify/{alipay,wechat}` | 渠道签名 |
| 健康检查 | `/api/health` | 无 |

完整清单与状态机见 `docs/系统详细设计文档.md`。

---

## 7. 已知限制

- 单机部署（文件存本地磁盘、定时任务未做分布式选主）。要多实例需先迁对象存储 + 定时任务加锁。
- 未接 T+1 对账单，核销依赖实时回调 + 主动查询兜底。
- 打印效果（份数、双面、纸张）最终取决于打印机驱动；上线前需在目标机型上按验收清单逐项试打。

---

## 8. 文档

| 文档 | 内容 |
|---|---|
| `docs/系统详细设计文档.md` | 架构、数据模型、核心流程、状态机、关键设计决策、接口清单、安全设计 |
| `docs/部署与使用说明书.md` | 三种部署方式、支付/短信/打印助手接入、运维备份、故障排查、上线检查表 |
| `docs/测试报告.md` | 测试环境与方法、17 项单测、55 项端到端断言、缺陷登记、遗留风险 |
