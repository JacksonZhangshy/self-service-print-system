package com.printsys.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** printsys.* 配置映射，字段含义见 application.yml 注释。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "printsys")
public class PrintSysProperties {
    private String storageDir = "./data/uploads";
    private Agent agent = new Agent();
    private Auth auth = new Auth();
    private Sms sms = new Sms();
    private Convert convert = new Convert();
    private Pay pay = new Pay();
    private Order order = new Order();
    private Task task = new Task();
    private Refund refund = new Refund();
    private FileStore file = new FileStore();

    @Getter
    @Setter
    public static class Agent {
        /** 打印助手调用 /api/agent/** 的共享密钥，生产必须改为随机长串 */
        private String token = "change-me-agent-token";
    }

    @Getter
    @Setter
    public static class Auth {
        private boolean demoMode = false;
        private String demoCode = "123456";
        /** 会话绝对有效期（小时） */
        private long sessionTtlHours = 12;
        /** 空闲多少分钟后会话失效（公共电脑关键防护） */
        private long idleMinutes = 15;
        private Admin admin = new Admin();
    }

    @Getter
    @Setter
    public static class Admin {
        /** 仅用于首次启动时初始化管理员账号，之后以数据库为准 */
        private String username = "admin";
        private String password = "admin123";
    }

    @Getter
    @Setter
    public static class Sms {
        /** log / aliyun / tencent / webhook */
        private String provider = "log";
        private int codeTtlMinutes = 5;
        private int resendSeconds = 60;
        private int maxPerHourPerPhone = 10;
        private int maxPerHourPerIp = 30;
        private int maxVerifyAttempts = 5;
        private String codeSalt = "printsys";
        private Aliyun aliyun = new Aliyun();
        private Tencent tencent = new Tencent();
        private Webhook webhook = new Webhook();

        @Getter
        @Setter
        public static class Aliyun {
            private String accessKeyId = "";
            private String accessKeySecret = "";
            private String signName = "";
            private String templateCode = "";
            private String endpoint = "https://dysmsapi.aliyuncs.com";
        }

        @Getter
        @Setter
        public static class Tencent {
            private String secretId = "";
            private String secretKey = "";
            private String sdkAppId = "";
            private String signName = "";
            private String templateId = "";
            private String region = "ap-guangzhou";
        }

        @Getter
        @Setter
        public static class Webhook {
            private String url = "";
            private String token = "";
        }
    }

    @Getter
    @Setter
    public static class Convert {
        /** 是否启用服务端 LibreOffice 转换（启用后计价页数 = 实际打印页数） */
        private boolean enabled = true;
        private String sofficePath = "";
        private int timeoutSeconds = 180;
        /** 转换不可用时是否允许按估算页数继续下单（false = 直接拒绝并提示用户传 PDF） */
        private boolean allowEstimate = false;
    }

    @Getter
    @Setter
    public static class Pay {
        /** 模拟支付开关：生产必须为 false，否则存在「免费打印」风险 */
        private boolean mockEnabled = true;
        private String publicBaseUrl = "http://localhost:8080";
        private Alipay alipay = new Alipay();
        private Wechat wechat = new Wechat();

        @Getter
        @Setter
        public static class Alipay {
            private boolean enabled = true;
            private String appId = "";
            private String privateKey = "";
            private String alipayPublicKey = "";
            private String sellerId = "";
            private String gateway = "https://openapi.alipay.com/gateway.do";

            public boolean isRealConfigured() {
                return enabled && notBlank(appId) && notBlank(privateKey) && notBlank(alipayPublicKey);
            }
        }

        @Getter
        @Setter
        public static class Wechat {
            private boolean enabled = true;
            private String appId = "";
            private String mchId = "";
            private String apiV3Key = "";
            private String merchantSerialNo = "";
            private String privateKey = "";
            /** 微信支付平台证书，多个用英文逗号分隔（支持证书轮换期间并存） */
            private String platformCerts = "";

            public boolean isRealConfigured() {
                return enabled && notBlank(appId) && notBlank(mchId) && notBlank(apiV3Key)
                        && notBlank(merchantSerialNo) && notBlank(privateKey) && notBlank(platformCerts);
            }

            public java.util.List<String> platformCertList() {
                java.util.List<String> list = new java.util.ArrayList<>();
                for (String s : platformCerts.split("(?<=-----END CERTIFICATE-----)")) {
                    if (notBlank(s)) {
                        list.add(s.trim());
                    }
                }
                if (list.isEmpty() && notBlank(platformCerts)) {
                    list.add(platformCerts.trim());
                }
                return list;
            }
        }
    }

    @Getter
    @Setter
    public static class Order {
        /** 未支付订单存活分钟数，到期自动关单 */
        private int expireMinutes = 10;
        /** 单次上传允许的最大页数，防止恶意超长文档 */
        private int maxPages = 500;
    }

    @Getter
    @Setter
    public static class Task {
        /** 助手领取后多久未回传视为僵尸任务，回收重试 */
        private int claimTimeoutMinutes = 15;
        private int maxRetry = 2;
    }

    @Getter
    @Setter
    public static class Refund {
        /** 打印失败自动原路退款 */
        private boolean autoOnPrintFailure = true;
    }

    @Getter
    @Setter
    public static class FileStore {
        /** 文件保留天数，超期自动清理（个人信息最小化留存） */
        private int retentionDays = 7;
        /** 单文件大小上限（MB） */
        private int maxMb = 100;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
