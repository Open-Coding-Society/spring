package com.nighthawk.spring_portfolio.mvc.bank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BankService {

    private static final Logger logger = LoggerFactory.getLogger(BankService.class);

    @Autowired
    private BankJpaRepository bankRepository;

    // Find by Person ID
    public Bank findByPersonId(Long personId) {
        Bank bank = bankRepository.findByPersonId(personId);
        if (bank == null) {
            logger.error("No bank account found for Person ID: {}", personId);
            throw new RuntimeException("Bank account not found for Person ID: " + personId);
        }
        return bank;
    }

    // Request a loan using the Person ID
    @Transactional
    public Bank requestLoan(Long personId, double loanAmount) {
        // Validate input
        if (personId == null) {
            logger.error("Invalid Person ID provided for loan request");
            throw new IllegalArgumentException("Person ID cannot be null");
        }

        if (loanAmount <= 0) {
            logger.error("Invalid loan amount: {}", loanAmount);
            throw new IllegalArgumentException("Loan amount must be positive");
        }

        // Find bank account
        Bank bank = bankRepository.findByPersonId(personId);
        if (bank == null) {
            logger.error("No bank account found for Person ID: {}", personId);
            throw new RuntimeException("Bank account not found for Person ID: " + personId);
        }

        // Process loan request
        try {
            bank.requestLoan(loanAmount);
            logger.info("Loan request processed for Person ID: {} amount: {}", personId, loanAmount);
            return bankRepository.save(bank);
        } catch (Exception e) {
            logger.error("Error processing loan request", e);
            throw new RuntimeException("Failed to process loan request", e);
        }
    }
    
    // Repay a loan using the Person ID
    @Transactional
    public Bank repayLoan(Long personId, double repaymentAmount) {
        // Validate input
        if (personId == null) {
            logger.error("Invalid Person ID provided for loan repayment");
            throw new IllegalArgumentException("Person ID cannot be null");
        }

        if (repaymentAmount <= 0) {
            logger.error("Invalid repayment amount: {}", repaymentAmount);
            throw new IllegalArgumentException("Repayment amount must be positive");
        }

        // Find bank account
        Bank bank = bankRepository.findByPersonId(personId);
        if (bank == null) {
            logger.error("No bank account found for Person ID: {}", personId);
            throw new RuntimeException("Bank account not found for Person ID: " + personId);
        }

        // Process loan repayment
        try {
            bank.repayLoan(repaymentAmount);
            logger.info("Loan repayment processed for Person ID: {} amount: {}", personId, repaymentAmount);
            return bankRepository.save(bank);
        } catch (Exception e) {
            logger.error("Error processing loan repayment", e);
            throw new RuntimeException("Failed to process loan repayment: " + e.getMessage(), e);
        }
    }
}