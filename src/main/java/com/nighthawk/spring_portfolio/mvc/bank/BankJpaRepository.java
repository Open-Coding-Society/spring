package com.nighthawk.spring_portfolio.mvc.bank;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BankJpaRepository extends JpaRepository<Bank, Long> {
    // Find bank by person_id
    Bank findByPersonId(Long personId);
    Bank findByUsername(String username);
}