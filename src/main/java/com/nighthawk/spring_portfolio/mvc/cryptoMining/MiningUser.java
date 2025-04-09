package com.nighthawk.spring_portfolio.mvc.cryptoMining;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@Data
@Entity
@NoArgsConstructor
public class MiningUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "person_id", unique = true)
    private Person person;

    // Legacy fields - keeping for backward compatibility
    private double btcBalance = 0.0;
    private double pendingBalance = 0.0;
    
    private boolean isMining = false;
    private String currentPool = "nicehash";
    private int shares = 0;
    private double currentHashrate = 0.0;
    private double dailyRevenue;
    private double powerCost;
    
    // New field for currently mined cryptocurrency
    @ManyToOne
    @JoinColumn(name = "current_crypto_id")
    private Cryptocurrency currentCryptocurrency;
    
    // Relationship with CryptoBalance - this will replace btcBalance and pendingBalance
    @OneToMany(mappedBy = "miningUser", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<CryptoBalance> cryptoBalances = new ArrayList<>();
    
    @ManyToMany
    private List<GPU> ownedGPUs = new ArrayList<>();

    @ManyToMany
    private List<GPU> activeGPUs = new ArrayList<>();

    // Mining statistics fields
    private long totalMiningTimeMinutes = 0;
    private long totalSharesMined = 0;
    private double totalBtcEarned = 0.0;
    private Date miningStartTime;
    private long miningSessionCount = 0;
    
    // Getters for calculated statistics
    public long getCurrentSessionDuration() {
        if (!isMining || miningStartTime == null) {
            return 0;
        }
        return (new Date().getTime() - miningStartTime.getTime()) / (1000 * 60); // in minutes
    }

    public double getAverageHashrate() {
        if (totalMiningTimeMinutes == 0) return 0;
        return (totalSharesMined * 1.0) / totalMiningTimeMinutes;
    }

    public MiningUser(Person person) {
        this.person = person;
        this.btcBalance = 0.0;
        this.pendingBalance = 0.0;
        this.shares = 0;
        this.isMining = false;
        this.currentPool = "default";
    }

    // Add this explicit getter for the controller
    public List<GPU> getGpus() {
        return this.ownedGPUs;
    }

    // Add this new field to track GPU quantities
    @ElementCollection
    private Map<Long, Integer> gpuQuantities = new HashMap<>();

    // Modify addGPU method
    public void addGPU(GPU gpu) {
        if (ownedGPUs == null) {
            ownedGPUs = new ArrayList<>();
        }
        if (activeGPUs == null) {
            activeGPUs = new ArrayList<>();
        }
        if (gpuQuantities == null) {
            gpuQuantities = new HashMap<>();
        }

        // Update quantity
        gpuQuantities.merge(gpu.getId(), 1, Integer::sum);

        // Add to owned GPUs list if not already there
        if (!ownedGPUs.contains(gpu)) {
            ownedGPUs.add(gpu);
        }

        // Always add new GPUs to active GPUs list
        activeGPUs.add(gpu);
        
        updateHashrate();
    }

    // Update getGpuQuantity method
    public int getGpuQuantity(Long gpuId) {
        return gpuQuantities.getOrDefault(gpuId, 0);
    }

    // Add method to get all GPU quantities
    public Map<Long, Integer> getGpuQuantities() {
        return gpuQuantities != null ? gpuQuantities : new HashMap<>();
    }

    // Update ownsGPUById method to check quantities
    public boolean ownsGPUById(Long gpuId) {
        return getGpuQuantity(gpuId) > 0;
    }

    private void updateHashrate() {
        this.currentHashrate = activeGPUs.stream()
            .mapToDouble(GPU::getHashRate)
            .sum();
    }

    public double getPowerConsumption() {
        return ownedGPUs.stream()
            .mapToInt(GPU::getPowerConsumption)
            .sum();
    }

    public double getAverageTemperature() {
        return ownedGPUs.stream()
            .mapToInt(GPU::getTemp)
            .average()
            .orElse(0);
    }

    public double getCurrentHashrate() {
        if (!isMining) return 0.0;
        return activeGPUs.stream()
            .mapToDouble(GPU::getHashRate)
            .sum();
    }

    public boolean ownsGPU(GPU gpu) {
        return this.ownedGPUs.contains(gpu);
    }

    // Update toggleGPU method to handle multiple instances
    public boolean toggleGPU(GPU gpu) {
        if (!ownedGPUs.contains(gpu)) {
            throw new RuntimeException("You don't own this GPU");
        }

        int quantity = getGpuQuantity(gpu.getId());
        boolean isActive = activeGPUs.contains(gpu);
        
        if (isActive) {
            // Remove all instances of this GPU from active GPUs
            activeGPUs.removeIf(g -> g.getId().equals(gpu.getId()));
        } else {
            // Add the correct number of instances to active GPUs
            for (int i = 0; i < quantity; i++) {
                activeGPUs.add(gpu);
            }
        }
        
        updateHashrate();
        return !isActive;
    }

    public boolean isGPUActive(GPU gpu) {
        return activeGPUs.contains(gpu);
    }

    public List<GPU> getActiveGPUs() {
        return this.activeGPUs;
    }

    // Modified setMining method to track sessions
    public void setMining(boolean mining) {
        if (mining && !this.isMining) {
            // Starting new mining session
            this.miningStartTime = new Date();
            this.miningSessionCount++;
        } else if (!mining && this.isMining) {
            // Stopping mining session
            if (miningStartTime != null) {
                this.totalMiningTimeMinutes += getCurrentSessionDuration();
            }
            this.miningStartTime = null;
        }
        this.isMining = mining;
        if (!mining) {
            this.currentHashrate = 0.0;
        } else {
            updateHashrate();
        }
    }
    
    // New methods for cryptocurrency management
    
    // Get a CryptoBalance for a specific cryptocurrency
    public CryptoBalance getBalanceForCrypto(Cryptocurrency crypto) {
        return cryptoBalances.stream()
            .filter(balance -> balance.getCryptocurrency().getId().equals(crypto.getId()))
            .findFirst()
            .orElse(null);
    }
    
    // Add balance to a specific cryptocurrency
    public void addCryptoBalance(Cryptocurrency crypto, double amount, boolean isPending) {
        CryptoBalance balance = getBalanceForCrypto(crypto);
        
        if (balance == null) {
            balance = new CryptoBalance(this, crypto);
            cryptoBalances.add(balance);
        }
        
        if (isPending) {
            balance.setPendingBalance(balance.getPendingBalance() + amount);
        } else {
            balance.setConfirmedBalance(balance.getConfirmedBalance() + amount);
        }
    }
    
    // Get total USD value of all cryptocurrencies
    public double getTotalCryptoValueUSD() {
        return cryptoBalances.stream()
            .mapToDouble(CryptoBalance::getTotalBalanceUSD)
            .sum();
    }
    
    // Set current cryptocurrency to mine
    public void setCurrentCryptocurrency(Cryptocurrency crypto) {
        this.currentCryptocurrency = crypto;
    }

    public double getDailyRevenue() {
        return dailyRevenue;
    }

    public void setDailyRevenue(double dailyRevenue) {
        this.dailyRevenue = dailyRevenue;
    }

    public double getPowerCost() {
        return powerCost;
    }

    public void setPowerCost(double powerCost) {
        this.powerCost = powerCost;
    }

    public List<GPU> getOwnedGPUs() {
        return this.ownedGPUs;
    }

    // Remove GPUs method
    public void removeGPUs(GPU gpu, int quantityToRemove) {
        if (ownedGPUs == null || activeGPUs == null || gpuQuantities == null) {
            throw new RuntimeException("User GPU collections not initialized");
        }

        int currentQuantity = gpuQuantities.getOrDefault(gpu.getId(), 0);
        if (currentQuantity < quantityToRemove) {
            throw new RuntimeException("Not enough GPUs to remove");
        }

        // Remove from active GPUs first
        int activeCount = (int) activeGPUs.stream()
            .filter(g -> g.getId().equals(gpu.getId()))
            .count();
        int toRemoveFromActive = Math.min(activeCount, quantityToRemove);
        
        // Remove from active GPUs
        for (int i = 0; i < toRemoveFromActive; i++) {
            activeGPUs.removeIf(g -> g.getId().equals(gpu.getId()));
        }

        // Update quantity
        int newQuantity = currentQuantity - quantityToRemove;
        if (newQuantity > 0) {
            gpuQuantities.put(gpu.getId(), newQuantity);
        } else {
            gpuQuantities.remove(gpu.getId());
            ownedGPUs.removeIf(g -> g.getId().equals(gpu.getId()));
        }

        updateHashrate();
    }
}