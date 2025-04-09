package com.nighthawk.spring_portfolio.mvc.cryptoMining;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CryptoBalanceRepository extends JpaRepository<CryptoBalance, Long> {
    List<CryptoBalance> findByMiningUser(MiningUser miningUser);
    Optional<CryptoBalance> findByMiningUserAndCryptocurrency(MiningUser miningUser, Cryptocurrency cryptocurrency);
} 