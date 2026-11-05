package com.cashflowai.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds payment-plan offers that always stay inside the owner's limits.
 * A request outside the limits gets the closest allowed counter-offer, never a "yes".
 * Pure Java so it can be unit tested.
 */
public final class PaymentPlanPolicy {

    public record Limits(int maxInstallments, int maxDays, BigDecimal minFirstShare) {
        public static final Limits DEFAULT = new Limits(2, 30, new BigDecimal("0.50"));
    }

    public record Installment(BigDecimal amount, LocalDate dueDate) {
    }

    public record Offer(List<Installment> installments, boolean adjusted, String note) {
        public BigDecimal firstPayment() {
            return installments.isEmpty() ? BigDecimal.ZERO : installments.get(0).amount();
        }
    }

    private PaymentPlanPolicy() {
    }

    public static Offer offer(BigDecimal outstanding, int requestedInstallments, int requestedDays,
                              LocalDate today, Limits limits) {
        if (outstanding == null || outstanding.signum() <= 0) {
            return new Offer(List.of(), false, "Nothing is outstanding on this invoice.");
        }
        int n = Math.max(1, Math.min(requestedInstallments, limits.maxInstallments()));
        int days = Math.max(0, Math.min(requestedDays, limits.maxDays()));
        if (n > 1 && days == 0) {
            days = limits.maxDays();
        }
        boolean adjusted = n != requestedInstallments || days != requestedDays;

        List<Installment> plan = new ArrayList<>();
        if (n == 1) {
            plan.add(new Installment(outstanding, today.plusDays(days)));
        } else {
            BigDecimal evenShare = outstanding.divide(BigDecimal.valueOf(n), 2, RoundingMode.UP);
            BigDecimal minFirst = outstanding.multiply(limits.minFirstShare()).setScale(2, RoundingMode.UP);
            BigDecimal first = evenShare.max(minFirst);
            plan.add(new Installment(first, today));

            BigDecimal rest = outstanding.subtract(first);
            int remaining = n - 1;
            BigDecimal each = rest.divide(BigDecimal.valueOf(remaining), 2, RoundingMode.DOWN);
            BigDecimal allocated = BigDecimal.ZERO;
            for (int i = 1; i <= remaining; i++) {
                BigDecimal amount = i == remaining ? rest.subtract(allocated) : each;
                allocated = allocated.add(amount);
                plan.add(new Installment(amount, today.plusDays((long) days * i / remaining)));
            }
        }

        String note = adjusted
                ? "Adjusted to the owner's limits: at most " + limits.maxInstallments() + " installments within "
                + limits.maxDays() + " days, first payment at least "
                + limits.minFirstShare().movePointRight(2).stripTrailingZeros().toPlainString() + "%."
                : "Within the owner's payment-plan limits.";
        return new Offer(plan, adjusted, note);
    }
}
