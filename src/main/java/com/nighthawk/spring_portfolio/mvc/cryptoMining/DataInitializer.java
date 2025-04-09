package com.nighthawk.spring_portfolio.mvc.cryptoMining;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {
    @Autowired
    private GPURepository gpuRepository;

    @Autowired
    private CryptocurrencyRepository cryptocurrencyRepository;

    @Override
    public void run(String... args) {
        // Only initialize if no GPUs exist
        if (gpuRepository.count() == 0) {
            initializeGPUs();
        }

        // Only initialize if no cryptocurrencies exist
        if (cryptocurrencyRepository.count() == 0) {
            initializeCryptocurrencies();
        }
    }

    private void initializeGPUs() {
       // Budget GPUs ($10000-20000)
       createGPU("NVIDIA GeForce GT 1030", 1.55, 30, 65, 10000, "Budget GPUs ($10000-20000)");
       createGPU("NVIDIA GeForce GTX 1050", 14, 75, 67, 10000, "Budget GPUs ($10000-20000)");
       createGPU("AMD RX 570 8GB", 28, 120, 70, 15000, "Budget GPUs ($10000-20000)");
       createGPU("NVIDIA GeForce GTX 1060 6GB", 22, 120, 68, 20000, "Budget GPUs ($10000-20000)");

       // Mid-Range GPUs ($20000-50000)
       createGPU("NVIDIA GeForce GTX 1660 SUPER", 31, 125, 69, 25000, "Mid-Range GPUs ($20000-50000)");
       createGPU("AMD RX 5600 XT", 40, 150, 71, 30000, "Mid-Range GPUs ($20000-50000)");
       createGPU("NVIDIA RTX 2060", 32, 160, 70, 35000, "Mid-Range GPUs ($20000-50000)");
       createGPU("NVIDIA RTX 2070", 42, 175, 71, 45000, "Mid-Range GPUs ($20000-50000)");

       // High-End GPUs ($50000-100000)
       createGPU("NVIDIA RTX 3060 Ti", 60, 200, 70, 55000, "High-End GPUs ($50000-100000)");
       createGPU("NVIDIA RTX 3070", 62, 220, 71, 65000, "High-End GPUs ($50000-100000)");
       createGPU("NVIDIA RTX 3080", 64, 300, 73, 80000, "High-End GPUs ($50000-100000)");
       createGPU("NVIDIA RTX 3090", 98, 320, 72, 95000, "High-End GPUs ($50000-100000)");

       // Premium GPUs ($100000+)
       createGPU("NVIDIA RTX 4070", 100, 285, 71, 120000, "Premium GPUs ($100000+)");
       createGPU("AMD RX 7900 XTX", 110, 355, 73, 150000, "Premium GPUs ($100000+)");
       createGPU("NVIDIA RTX 4080", 130, 320, 73, 180000, "Premium GPUs ($100000+)");
       createGPU("NVIDIA RTX 4090", 140, 450, 75, 200000, "Premium GPUs ($100000+)");
   }

    private void createGPU(String name, double hashRate, int power, int temp, double price, String category) {
        GPU gpu = new GPU();
        gpu.setName(name);
        gpu.setHashRate(hashRate);
        gpu.setPowerConsumption(power);
        gpu.setTemp(temp);
        gpu.setPrice(price);
        gpu.setCategory(category);
        gpuRepository.save(gpu);
    }

    private void initializeCryptocurrencies() {
        // Bitcoin - the original cryptocurrency
        createCryptocurrency(
            "Bitcoin", 
            "BTC", 
            45000.0, 
            "https://cryptologos.cc/logos/bitcoin-btc-logo.png",
            "SHA-256", 
            6.25, 
            "Very High", 
            0.001
        );

        // Ethereum - smart contract platform
        createCryptocurrency(
            "Ethereum", 
            "ETH", 
            3000.0, 
            "https://cryptologos.cc/logos/ethereum-eth-logo.png",
            "Ethash", 
            2.0, 
            "High", 
            0.01
        );

        // Litecoin - faster alternative to Bitcoin
        createCryptocurrency(
            "Litecoin", 
            "LTC", 
            80.0, 
            "https://cryptologos.cc/logos/litecoin-ltc-logo.png",
            "Scrypt", 
            12.5, 
            "Medium", 
            0.02
        );

        // Monero - privacy-focused cryptocurrency
        createCryptocurrency(
            "Monero", 
            "XMR", 
            170.0, 
            "https://cryptologos.cc/logos/monero-xmr-logo.png",
            "RandomX", 
            0.6, 
            "Medium", 
            0.01
        );
    }

    private void createCryptocurrency(String name, String symbol, double price, String logoUrl, 
                                    String miningAlgorithm, double blockReward, 
                                    String difficulty, double minPayout) {
        Cryptocurrency crypto = new Cryptocurrency(name, symbol, price, logoUrl, 
                                                 miningAlgorithm, blockReward, 
                                                 difficulty, minPayout);
        cryptocurrencyRepository.save(crypto);
    }
}