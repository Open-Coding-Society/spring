package com.nighthawk.spring_portfolio.mvc.cryptoMining;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Date;

// Add these imports
import com.nighthawk.spring_portfolio.mvc.userStocks.UserStocksRepository;
import com.nighthawk.spring_portfolio.mvc.userStocks.userStocksTable;
import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;

@Service
@EnableScheduling
public class MiningService {
    @Autowired
    private MiningUserRepository miningUserRepository;
    
    @Autowired
    private GPURepository gpuRepository;

    @Autowired
    private UserStocksRepository userStocksRepo;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private CryptocurrencyRepository cryptocurrencyRepository;

    @Autowired
    private CryptoBalanceRepository cryptoBalanceRepository;

    // Fine-tune constants
    public static final double HASH_TO_BTC_RATE = 0.0001; // Current rate
    public static final double DIFFICULTY_FACTOR = 1.0;
    public static final int MINING_INTERVAL = 10000; // 10 seconds
    public static final int BALANCE_TRANSFER_INTERVAL = 60000; // 10 sec
    public static final double ELECTRICITY_RATE = 0.12; // USD per kWh
    public static final double BTC_PRICE = 45000.0;

    // Hashrate to crypto mining rates - each crypto has a different efficiency
    private final Map<String, Double> CRYPTO_MINING_RATES = Map.of(
        "BTC", 0.0001,  // Bitcoin
        "ETH", 0.001,   // Ethereum
        "LTC", 0.01,    // Litecoin
        "XMR", 0.005    // Monero
    );

    @Scheduled(fixedRate = MINING_INTERVAL)
    @Transactional
    public void processMining() {
        List<MiningUser> activeMiners = miningUserRepository.findAll().stream()
            .filter(user -> user.isMining() && !user.getActiveGPUs().isEmpty())
            .collect(Collectors.toList());

        activeMiners.forEach(miner -> {
            double hashrate = miner.getCurrentHashrate();
            
            if (hashrate > 0) {
                // Get the current cryptocurrency being mined or default to Bitcoin
                Cryptocurrency currentCrypto = miner.getCurrentCryptocurrency();
                if (currentCrypto == null) {
                    currentCrypto = cryptocurrencyRepository.findBySymbol("BTC")
                        .orElseThrow(() -> new RuntimeException("Bitcoin not found in database"));
                    miner.setCurrentCryptocurrency(currentCrypto);
                }
                
                // Get mining rate for this crypto
                double miningRate = CRYPTO_MINING_RATES.getOrDefault(
                    currentCrypto.getSymbol(), HASH_TO_BTC_RATE);
                
                // Calculate mining reward for specific crypto
                double cryptoMined = hashrate * miningRate / 60.0;
                
                // Add to pending balance for this crypto
                miner.addCryptoBalance(currentCrypto, cryptoMined, true);
                
                // For backward compatibility - maintain BTC balance if mining BTC
                if ("BTC".equals(currentCrypto.getSymbol())) {
                    miner.setPendingBalance(miner.getPendingBalance() + cryptoMined);
                }
                
                // Convert crypto to USD
                double usdMined = cryptoMined * currentCrypto.getPrice();
                
                // Update Person's USD balance
                Person person = miner.getPerson();
                double currentBalance = person.getBalanceDouble();
                person.setBalanceString(currentBalance + usdMined);
                personRepository.save(person);
                
                // Update mining stats
                if ("BTC".equals(currentCrypto.getSymbol())) {
                    miner.setTotalBtcEarned(miner.getTotalBtcEarned() + cryptoMined);
                }
                miner.setShares(miner.getShares() + 1);
                miner.setTotalSharesMined(miner.getTotalSharesMined() + 1);
            }
            
            miningUserRepository.save(miner);
        });
    }

    @Scheduled(fixedRate = BALANCE_TRANSFER_INTERVAL)
    @Transactional
    public void processPendingBalances() {
        List<MiningUser> miners = miningUserRepository.findAll();
        
        miners.forEach(miner -> {
            // Process all crypto balances
            miner.getCryptoBalances().forEach(balance -> {
                double pending = balance.getPendingBalance();
                if (pending > 0) {
                    try {
                        // Transfer from pending to confirmed for this crypto
                        double currentConfirmed = balance.getConfirmedBalance();
                        double newConfirmed = currentConfirmed + pending;
                        balance.setConfirmedBalance(newConfirmed);
                        
                        // Calculate USD value
                        double pendingUSD = pending * balance.getCryptocurrency().getPrice();
                        
                        // Get user's stocks/balance record for USD update
                        userStocksTable userStocks = userStocksRepo.findByEmail(miner.getPerson().getEmail());
                        if (userStocks != null) {
                            double oldBalance = Double.parseDouble(userStocks.getBalance());
                            double newBalance = oldBalance + pendingUSD;
                            
                            // Update USD balance
                            userStocks.setBalance(String.format("%.2f", newBalance));
                            userStocksRepo.save(userStocks);
                        }
                        
                        // Clear pending crypto
                        balance.setPendingBalance(0.0);
                        
                        // For BTC compatibility
                        if ("BTC".equals(balance.getCryptocurrency().getSymbol())) {
                            miner.setBtcBalance(miner.getBtcBalance() + pending);
                            miner.setPendingBalance(0.0);
                        }
                    } catch (Exception e) {
                        // Silently handle error
                    }
                }
            });
            
            miningUserRepository.save(miner);
        });
    }

    public Map<String, Object> buyGPU(MiningUser user, Long gpuId) {
        GPU gpu = gpuRepository.findById(gpuId).orElse(null);
        if (gpu == null) {
            return Map.of("success", false, "message", "GPU not found");
        }

        user.addGPU(gpu);
        miningUserRepository.save(user);

        return Map.of(
            "success", true,
            "message", "Successfully purchased " + gpu.getName(),
            "newBalance", user.getBtcBalance()
        );
    }

    public void calculateProfitability(MiningUser miner) {
        double hashrate = miner.getCurrentHashrate();
        
        // Get current cryptocurrency
        Cryptocurrency currentCrypto = miner.getCurrentCryptocurrency();
        if (currentCrypto == null) {
            currentCrypto = cryptocurrencyRepository.findBySymbol("BTC")
                .orElseThrow(() -> new RuntimeException("Bitcoin not found in database"));
        }
        
        // Get mining rate for this crypto
        double miningRate = CRYPTO_MINING_RATES.getOrDefault(
            currentCrypto.getSymbol(), HASH_TO_BTC_RATE);
        
        // Calculate daily earnings (24 hours)
        double dailyCrypto = hashrate * miningRate * 24.0;
        double dailyUSD = dailyCrypto * currentCrypto.getPrice();
        
        // Calculate power costs
        double dailyPowerKWH = miner.getActiveGPUs().stream()
            .mapToDouble(gpu -> gpu.getPowerConsumption() * 24.0 / 1000.0)
            .sum();
        double dailyPowerCost = dailyPowerKWH * ELECTRICITY_RATE;
        
        // Set values
        miner.setDailyRevenue(dailyUSD);
        miner.setPowerCost(dailyPowerCost);
    }
    
    // Change current cryptocurrency for mining
    @Transactional
    public Map<String, Object> changeMiningCrypto(MiningUser user, String symbol) {
        Optional<Cryptocurrency> cryptoOpt = cryptocurrencyRepository.findBySymbol(symbol);
        
        if (cryptoOpt.isEmpty()) {
            return Map.of(
                "success", false,
                "message", "Cryptocurrency with symbol " + symbol + " not found"
            );
        }
        
        Cryptocurrency crypto = cryptoOpt.get();
        user.setCurrentCryptocurrency(crypto);
        miningUserRepository.save(user);
        
        return Map.of(
            "success", true,
            "message", "Now mining " + crypto.getName(),
            "symbol", crypto.getSymbol()
        );
    }
    
    // Get all available cryptocurrencies
    public List<Map<String, Object>> getAvailableCryptocurrencies() {
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (Cryptocurrency crypto : cryptocurrencyRepository.findByActiveTrue()) {
            Map<String, Object> cryptoMap = new HashMap<>();
            cryptoMap.put("id", crypto.getId());
            cryptoMap.put("name", crypto.getName());
            cryptoMap.put("symbol", crypto.getSymbol());
            cryptoMap.put("price", crypto.getPrice());
            cryptoMap.put("logoUrl", crypto.getLogoUrl());
            cryptoMap.put("algorithm", crypto.getMiningAlgorithm());
            cryptoMap.put("blockReward", crypto.getBlockReward());
            cryptoMap.put("difficulty", crypto.getDifficulty());
            cryptoMap.put("minPayout", crypto.getMinPayout());
            result.add(cryptoMap);
        }
        
        return result;
    }
}