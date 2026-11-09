package com.printsys.pay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradeCloseModel;
import com.alipay.api.domain.AlipayTradePrecreateModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.domain.AlipayTradeRefundModel;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeCloseResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.printsys.config.PrintSysProperties;
import com.printsys.entity.PayMethod;
import com.printsys.entity.PrintOrder;
import com.printsys.util.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 支付宝当面付（扫码）网关。
 * 真实模式：alipay.trade.precreate 下单（带 timeout_express 到期自动关单）、
 *          alipay.trade.query 轮询兜底、alipay.trade.close 关单、alipay.trade.refund 原路退款。
 * 模拟模式：未配置商户参数时自动启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlipayGateway implements PaymentGateway {
    private final PrintSysProperties props;

    @Override
    public PayMethod method() {
        return PayMethod.ALIPAY;
    }

    @Override
    public boolean isRealMode() {
        return props.getPay().getAlipay().isRealConfigured();
    }

    private AlipayClient client() {
        PrintSysProperties.Pay.Alipay a = props.getPay().getAlipay();
        return new DefaultAlipayClient(a.getGateway(), a.getAppId(), a.getPrivateKey(),
                "json", "UTF-8", a.getAlipayPublicKey(), "RSA2");
    }

    @Override
    public PrecreateResult precreate(PrintOrder order) {
        if (!isRealMode()) {
            log.info("支付宝未配置完整商户参数，订单 {} 使用模拟支付", order.getOrderNo());
            return MockPaySupport.mockResult(props, order, PayMethod.ALIPAY);
        }
        try {
            AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
            request.setNotifyUrl(props.getPay().getPublicBaseUrl() + "/api/pay/alipay/notify");
            AlipayTradePrecreateModel model = new AlipayTradePrecreateModel();
            model.setOutTradeNo(order.getOrderNo());
            model.setTotalAmount(order.getAmount().toPlainString());
            model.setSubject("自助打印-" + order.getOrderNo());
            // 二维码有效期与订单超时时间一致，过期后支付宝侧自动关单
            model.setTimeoutExpress(props.getOrder().getExpireMinutes() + "m");
            request.setBizModel(model);
            AlipayTradePrecreateResponse response = client().execute(request);
            if (!response.isSuccess()) {
                throw new BizException("支付宝下单失败: " + response.getSubMsg());
            }
            return new PrecreateResult(response.getQrCode(), "REAL");
        } catch (AlipayApiException e) {
            throw new BizException("支付宝下单异常: " + e.getErrMsg());
        }
    }

    @Override
    public Optional<String> queryPaidTradeNo(PrintOrder order) {
        if (!isRealMode()) {
            return Optional.empty();
        }
        try {
            AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
            AlipayTradeQueryModel model = new AlipayTradeQueryModel();
            model.setOutTradeNo(order.getOrderNo());
            request.setBizModel(model);
            AlipayTradeQueryResponse response = client().execute(request);
            if (response.isSuccess() && ("TRADE_SUCCESS".equals(response.getTradeStatus())
                    || "TRADE_FINISHED".equals(response.getTradeStatus()))) {
                return Optional.ofNullable(response.getTradeNo());
            }
            return Optional.empty();
        } catch (AlipayApiException e) {
            log.warn("支付宝订单查询失败 orderNo={}: {}", order.getOrderNo(), e.getErrMsg());
            return Optional.empty();
        }
    }

    @Override
    public void close(PrintOrder order) {
        if (!isRealMode()) {
            return;
        }
        try {
            AlipayTradeCloseRequest request = new AlipayTradeCloseRequest();
            AlipayTradeCloseModel model = new AlipayTradeCloseModel();
            model.setOutTradeNo(order.getOrderNo());
            request.setBizModel(model);
            AlipayTradeCloseResponse resp = client().execute(request);
            log.info("支付宝关单 orderNo={} success={} msg={}", order.getOrderNo(), resp.isSuccess(), resp.getSubMsg());
        } catch (AlipayApiException e) {
            log.warn("支付宝关单失败 orderNo={}: {}", order.getOrderNo(), e.getErrMsg());
        }
    }

    @Override
    public RefundResult refund(PrintOrder order, BigDecimal amount, String refundNo, String reason) {
        if (!isRealMode()) {
            return RefundResult.ok("MOCK-" + refundNo);
        }
        try {
            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            AlipayTradeRefundModel model = new AlipayTradeRefundModel();
            model.setOutTradeNo(order.getOrderNo());
            model.setRefundAmount(amount.toPlainString());
            model.setOutRequestNo(refundNo);
            model.setRefundReason(reason);
            request.setBizModel(model);
            AlipayTradeRefundResponse resp = client().execute(request);
            if (resp.isSuccess()) {
                return RefundResult.ok(resp.getTradeNo());
            }
            log.warn("支付宝退款失败 orderNo={} code={} subMsg={}", order.getOrderNo(), resp.getSubCode(), resp.getSubMsg());
            return RefundResult.fail(resp.getSubMsg() == null ? "退款失败" : resp.getSubMsg());
        } catch (AlipayApiException e) {
            log.error("支付宝退款异常 orderNo={}", order.getOrderNo(), e);
            return RefundResult.fail(e.getErrMsg());
        }
    }
}
