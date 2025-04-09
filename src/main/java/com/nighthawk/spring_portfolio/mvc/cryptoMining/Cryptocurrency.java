package com.nighthawk.spring_portfolio.mvc.cryptoMining;

import lombok.Data;
import jakarta.persistence.*;

@Data
@Entity
public class Cryptocurrency {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String symbol;
    private double price;  // USD price
    private String logoUrl;
    private String miningAlgorithm;
    private double blockReward;
    private String difficulty;
    private double minPayout;
    private boolean active = true;
    
    // Default constructor required by JPA
    public Cryptocurrency() {}

    // Constructor with fields
    public Cryptocurrency(String name, String symbol, double price, String logoUrl, 
                         String miningAlgorithm, double blockReward, 
                         String difficulty, double minPayout) {
        this.name = name;
        this.symbol = symbol;
        this.price = price;
        this.logoUrl = logoUrl;
        this.miningAlgorithm = miningAlgorithm;
        this.blockReward = blockReward;
        this.difficulty = difficulty;
        this.minPayout = minPayout;
        this.active = true;
    }
} 