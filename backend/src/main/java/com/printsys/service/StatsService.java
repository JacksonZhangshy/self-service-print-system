package com.printsys.service;

import com.printsys.entity.PrintOrder;
import com.printsys.entity.RefundStatus;
import com.printsys.repo.PrintOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 收益统计：以已支付订单（paidAt 非空）为口径，扣除已退款金额后给出净收入。 */
@Service
@RequiredArgsConstructor
public class StatsService {
    private final PrintOrderRepository orderRepository;

    public record Bucket(String label, BigDecimal amount, BigDecimal refunded, BigDecimal net, long orders) {
    }

    public List<Bucket> daily(int days) {
        int d = Math.min(Math.max(days, 1), 180);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(d - 1L);
        List<PrintOrder> paid = orderRepository.findPaidBetween(
                from.atStartOfDay(), today.plusDays(1).atStartOfDay());
        Map<LocalDate, List<PrintOrder>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < d; i++) {
            grouped.put(from.plusDays(i), new ArrayList<>());
        }
        for (PrintOrder o : paid) {
            grouped.computeIfAbsent(o.getPaidAt().toLocalDate(), k -> new ArrayList<>()).add(o);
        }
        List<Bucket> result = new ArrayList<>();
        grouped.forEach((day, list) -> result.add(bucket(day.toString(), list)));
        return result;
    }

    public List<Bucket> monthly(int months) {
        int m = Math.min(Math.max(months, 1), 36);
        YearMonth current = YearMonth.now();
        YearMonth from = current.minusMonths(m - 1L);
        List<PrintOrder> paid = orderRepository.findPaidBetween(
                from.atDay(1).atStartOfDay(), current.plusMonths(1).atDay(1).atStartOfDay());
        Map<YearMonth, List<PrintOrder>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < m; i++) {
            grouped.put(from.plusMonths(i), new ArrayList<>());
        }
        for (PrintOrder o : paid) {
            grouped.computeIfAbsent(YearMonth.from(o.getPaidAt()), k -> new ArrayList<>()).add(o);
        }
        List<Bucket> result = new ArrayList<>();
        grouped.forEach((month, list) -> result.add(bucket(month.toString(), list)));
        return result;
    }

    public Map<String, Object> summary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrow = LocalDate.now().plusDays(1).atStartOfDay();
        List<PrintOrder> todayPaid = orderRepository.findPaidBetween(todayStart, tomorrow);
        YearMonth ym = YearMonth.now();
        List<PrintOrder> monthPaid = orderRepository.findPaidBetween(
                ym.atDay(1).atStartOfDay(), ym.plusMonths(1).atDay(1).atStartOfDay());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("todayAmount", sumAmount(todayPaid));
        map.put("todayRefunded", sumRefunded(todayPaid));
        map.put("todayNet", sumAmount(todayPaid).subtract(sumRefunded(todayPaid)));
        map.put("todayOrders", todayPaid.size());
        map.put("monthAmount", sumAmount(monthPaid));
        map.put("monthRefunded", sumRefunded(monthPaid));
        map.put("monthNet", sumAmount(monthPaid).subtract(sumRefunded(monthPaid)));
        map.put("monthOrders", monthPaid.size());
        return map;
    }

    private Bucket bucket(String label, List<PrintOrder> list) {
        BigDecimal amount = sumAmount(list);
        BigDecimal refunded = sumRefunded(list);
        return new Bucket(label, amount, refunded, amount.subtract(refunded), list.size());
    }

    private BigDecimal sumAmount(List<PrintOrder> list) {
        return list.stream().map(PrintOrder::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sumRefunded(List<PrintOrder> list) {
        return list.stream()
                .filter(o -> o.getRefundStatus() == RefundStatus.SUCCESS && o.getRefundAmount() != null)
                .map(PrintOrder::getRefundAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
