package com.cashflowai.policy;

import com.cashflowai.domain.AutonomyRule;
import com.cashflowai.domain.AutonomyRuleRepository;
import com.cashflowai.domain.Enums.AutonomyMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Earned autonomy. Every promotable action starts as "needs approval". After a streak of
 * owner approvals with no rejection, the owner is offered to let the agent do it alone.
 * A single rejection resets the streak and takes autonomy away again.
 */
@Service
public class AutonomyService {

    public record RuleView(ActionType actionType, AutonomyMode mode, int approvalStreak, boolean suggestPromotion) {
    }

    private final AutonomyRuleRepository rules;
    private final int suggestAfter;

    public AutonomyService(AutonomyRuleRepository rules,
                           @Value("${cashflow.autonomy.suggest-after:3}") int suggestAfter) {
        this.rules = rules;
        this.suggestAfter = suggestAfter;
    }

    public boolean isGranted(ActionType type) {
        return ActionType.PROMOTABLE.contains(type)
                && rules.findById(type).map(r -> r.getMode() == AutonomyMode.AUTO).orElse(false);
    }

    @Transactional
    public void recordApproval(ActionType type) {
        if (!ActionType.PROMOTABLE.contains(type)) {
            return;
        }
        AutonomyRule rule = rule(type);
        rule.setApprovalStreak(rule.getApprovalStreak() + 1);
        rules.save(rule);
    }

    @Transactional
    public void recordRejection(ActionType type) {
        if (!ActionType.PROMOTABLE.contains(type)) {
            return;
        }
        AutonomyRule rule = rule(type);
        rule.setApprovalStreak(0);
        rule.setMode(AutonomyMode.APPROVAL);
        rules.save(rule);
    }

    @Transactional
    public RuleView setMode(ActionType type, AutonomyMode mode) {
        if (!ActionType.PROMOTABLE.contains(type)) {
            throw new IllegalArgumentException(type + " can never run without approval");
        }
        AutonomyRule rule = rule(type);
        rule.setMode(mode);
        return view(rules.save(rule));
    }

    public List<RuleView> all() {
        return ActionType.PROMOTABLE.stream().map(t -> view(rules.findById(t).orElse(new AutonomyRule(t)))).toList();
    }

    private AutonomyRule rule(ActionType type) {
        return rules.findById(type).orElseGet(() -> new AutonomyRule(type));
    }

    private RuleView view(AutonomyRule r) {
        boolean suggest = r.getMode() == AutonomyMode.APPROVAL && r.getApprovalStreak() >= suggestAfter;
        return new RuleView(r.getActionType(), r.getMode(), r.getApprovalStreak(), suggest);
    }
}
