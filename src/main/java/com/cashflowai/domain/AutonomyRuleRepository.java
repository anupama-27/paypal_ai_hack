package com.cashflowai.domain;

import com.cashflowai.policy.ActionType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutonomyRuleRepository extends JpaRepository<AutonomyRule, ActionType> {
}
