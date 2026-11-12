package com.cashflowai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TimelineOverrideRepository extends JpaRepository<TimelineOverride, Long> {

    Optional<TimelineOverride> findByKindAndSourceId(String kind, Long sourceId);
}
