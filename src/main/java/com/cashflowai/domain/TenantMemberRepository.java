package com.cashflowai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TenantMemberRepository extends JpaRepository<TenantMember, Long> {

    List<TenantMember> findByEmailIgnoreCase(String email);

    List<TenantMember> findByTenantIdOrderByEmailAsc(Long tenantId);

    boolean existsByTenantIdAndEmailIgnoreCase(Long tenantId, String email);
}
