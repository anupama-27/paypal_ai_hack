package com.cashflowai.domain;

import com.cashflowai.domain.Enums.ActionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentActionRepository extends JpaRepository<AgentAction, Long> {

    List<AgentAction> findByCaseIdOrderByCreatedAtAsc(Long caseId);

    List<AgentAction> findByStatusOrderByCreatedAtAsc(ActionStatus status);

    List<AgentAction> findByCaseIdAndToolNameAndStatusOrderByCreatedAtDesc(Long caseId, String toolName,
                                                                         ActionStatus status);
}
