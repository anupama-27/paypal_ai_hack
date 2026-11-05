package com.cashflowai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentActionRepository extends JpaRepository<AgentAction, Long> {

    List<AgentAction> findByCaseIdOrderByCreatedAtAsc(Long caseId);
}
