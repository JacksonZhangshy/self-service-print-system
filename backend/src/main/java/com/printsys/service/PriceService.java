package com.printsys.service;

import com.printsys.entity.PriceConfig;
import com.printsys.repo.PriceConfigRepository;
import com.printsys.util.BizException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 收费标准读写：单行配置，首次启动写入默认值，修改时做上下限校验。 */
@Service
@RequiredArgsConstructor
public class PriceService {
    private static final BigDecimal MAX_PRICE = new BigDecimal("100.00");
    private static final BigDecimal MIN_UNIT = new BigDecimal("0.01");

    private final PriceConfigRepository repo;

    @PostConstruct
    @Transactional
    public void initDefault() {
        if (repo.findById(1L).isEmpty()) {
            repo.save(new PriceConfig());
        }
    }

    public PriceConfig current() {
        return repo.findById(1L).orElseGet(() -> repo.save(new PriceConfig()));
    }

    @Transactional
    public PriceConfig update(PriceConfig incoming) {
        PriceConfig cfg = current();
        cfg.setBwA4(checkUnit(incoming.getBwA4(), "黑白 A4"));
        cfg.setBwA3(checkUnit(incoming.getBwA3(), "黑白 A3"));
        cfg.setColorA4(checkUnit(incoming.getColorA4(), "彩色 A4"));
        cfg.setColorA3(checkUnit(incoming.getColorA3(), "彩色 A3"));

        BigDecimal duplex = incoming.getDuplexDiscount();
        if (duplex == null || duplex.compareTo(new BigDecimal("0.10")) < 0
                || duplex.compareTo(BigDecimal.ONE) > 0) {
            throw new BizException("双面系数需在 0.10 ~ 1.00 之间");
        }
        cfg.setDuplexDiscount(duplex.setScale(2, java.math.RoundingMode.HALF_UP));

        BigDecimal min = incoming.getMinCharge() == null ? new BigDecimal("0.30") : incoming.getMinCharge();
        if (min.compareTo(new BigDecimal("0.01")) < 0 || min.compareTo(MAX_PRICE) > 0) {
            throw new BizException("最低消费需在 0.01 ~ 100.00 元之间");
        }
        cfg.setMinCharge(min.setScale(2, java.math.RoundingMode.HALF_UP));
        cfg.setUpdatedAt(LocalDateTime.now());
        return repo.save(cfg);
    }

    private BigDecimal checkUnit(BigDecimal v, String label) {
        if (v == null) {
            throw new BizException(label + "单价不能为空");
        }
        if (v.compareTo(MIN_UNIT) < 0 || v.compareTo(MAX_PRICE) > 0) {
            throw new BizException(label + "单价需在 0.01 ~ 100.00 元之间");
        }
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
