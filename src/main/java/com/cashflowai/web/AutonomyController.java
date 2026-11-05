package com.cashflowai.web;

import com.cashflowai.domain.Enums.AutonomyMode;
import com.cashflowai.policy.ActionType;
import com.cashflowai.policy.AutonomyService;
import com.cashflowai.policy.AutonomyService.RuleView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Earned autonomy: see streaks and promote/demote the actions the agent may do alone. */
@RestController
@RequestMapping("/api/autonomy")
public class AutonomyController {

    private final AutonomyService autonomy;

    public AutonomyController(AutonomyService autonomy) {
        this.autonomy = autonomy;
    }

    @GetMapping
    public List<RuleView> rules() {
        return autonomy.all();
    }

    @PostMapping("/{actionType}")
    public RuleView setMode(@PathVariable ActionType actionType, @RequestParam AutonomyMode mode) {
        return autonomy.setMode(actionType, mode);
    }
}
