package com.printsys.service.sms;

/** 短信通道扩展点：接入阿里云 / 腾讯云 / 自建网关只需实现该接口。 */
public interface SmsSender {

    /** 通道标识，用于日志与配置校验 */
    String name();

    /**
     * 发送登录验证码。
     *
     * @param phone 11 位手机号（不含国家码）
     * @param code  6 位数字验证码
     * @throws SmsSendException 通道返回失败时抛出，由上层转成用户可读错误
     */
    void sendCode(String phone, String code);
}
