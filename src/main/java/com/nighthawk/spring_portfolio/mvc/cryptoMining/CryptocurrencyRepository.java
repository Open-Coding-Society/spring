package com.nighthawk.spring_portfolio.mvc.cryptoMining;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CryptocurrencyRepository extends JpaRepository<Cryptocurrency, Long> {
    List<Cryptocurrency> findByActiveTrue();
    Optional<Cryptocurrency> findBySymbol(String symbol);
} 