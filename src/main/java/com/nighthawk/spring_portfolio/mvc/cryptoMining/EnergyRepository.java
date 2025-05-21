package com.nighthawk.spring_portfolio.mvc.cryptoMining;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EnergyRepository extends JpaRepository<Energy, Long> {
    List<Energy> findBySupplierName(String supplierName);
}