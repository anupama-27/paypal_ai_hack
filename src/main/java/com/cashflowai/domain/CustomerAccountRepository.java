package com.cashflowai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerAccountRepository extends JpaRepository<CustomerAccount, Long> {

    Optional<CustomerAccount> findByAccessKey(String accessKey);

    Optional<CustomerAccount> findByEmailIgnoreCase(String email);
}
