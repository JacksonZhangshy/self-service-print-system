package com.printsys.pay;

import com.printsys.config.PrintSysProperties;
import com.printsys.entity.PayMethod;
import com.printsys.entity.PrintOrder;

/** 模拟支付 URL 构造。 */
public final class MockPaySupport {
    private MockPaySupport() {
    }

    public static PrecreateResult mockResult(PrintSysProperties props, PrintOrder order, PayMethod method) {
        String url = props.getPay().getPublicBaseUrl()
                + "/mock-pay?orderNo=" + order.getOrderNo() + "&method=" + method.name();
        return new PrecreateResult(url, "MOCK");
    }
}
