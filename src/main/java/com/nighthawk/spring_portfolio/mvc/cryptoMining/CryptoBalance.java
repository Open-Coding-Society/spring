package com.nighthawk.spring_portfolio.mvc.cryptoMining;

import lombok.Data;
import jakarta.persistence.*;

@Data
@Entity
public class CryptoBalance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "mining_user_id")
    private MiningUser miningUser;

    @ManyToOne
    @JoinColumn(name = "cryptocurrency_id")
    private Cryptocurrency cryptocurrency;

    private double confirmedBalance = 0.0;
    private double pendingBalance = 0.0;

    // Default constructor required by JPA
    public CryptoBalance() {}

    // Constructor with fields
    public CryptoBalance(MiningUser miningUser, Cryptocurrency cryptocurrency) {
        this.miningUser = miningUser;
        this.cryptocurrency = cryptocurrency;
        this.confirmedBalance = 0.0;
        this.pendingBalance = 0.0;
    }

    // Calculate USD value of confirmed balance
    public double getConfirmedBalanceUSD() {
        return this.confirmedBalance * this.cryptocurrency.getPrice();
    }

    // Calculate USD value of pending balance
    public double getPendingBalanceUSD() {
        return this.pendingBalance * this.cryptocurrency.getPrice();
    }

    // Calculate total USD value
    public double getTotalBalanceUSD() {
        return getConfirmedBalanceUSD() + getPendingBalanceUSD();
    }
} 