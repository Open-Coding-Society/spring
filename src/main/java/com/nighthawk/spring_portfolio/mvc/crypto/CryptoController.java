package com.nighthawk.spring_portfolio.mvc.crypto;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;
import com.nighthawk.spring_portfolio.mvc.userStocks.UserStocksRepository;
import com.nighthawk.spring_portfolio.mvc.userStocks.userStocksTable;

@RestController


@RequestMapping("/api/crypto")
public class CryptoController {

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private UserStocksRepository userStocksRepo;

    @Autowired
    private CryptoJPArepo cryptoJPArepo;

    @GetMapping("/balance")
    public ResponseEntity<?> getUserBalance(@RequestParam String email) {
        userStocksTable userStocks = userStocksRepo.findByEmail(email);
    
        if (userStocks == null) {
            return ResponseEntity.status(404).body("User balance not found for email: " + email);
        }
    
        return ResponseEntity.ok("{ \"email\": \"" + email + "\", \"balance\": \"" + userStocks.getBalance() + "\" }");
    }
    
    @GetMapping("/balanceById")
    public ResponseEntity<?> getUserBalanceById(@RequestParam Long id) {
        userStocksTable userStocks = userStocksRepo.findById(id).orElse(null);

        if (userStocks == null) {
            return ResponseEntity.status(404).body("User balance not found for ID: " + id);
        }

        return ResponseEntity.ok("{ \"id\": \"" + id + "\", \"balance\": \"" + userStocks.getBalance() + "\" }");
    }
    
    @GetMapping("/live")
    public ResponseEntity<?> getLiveCryptoData() {
        Crypto[] cryptoData = cryptoService.getCryptoData();

        if (cryptoData == null || cryptoData.length == 0) {
            return ResponseEntity.status(500).body("Failed to fetch cryptocurrency data");
        }

        return ResponseEntity.ok(cryptoData);
    }

    @GetMapping("/trend")
    public ResponseEntity<?> getCryptoTrend(@RequestParam String cryptoId, @RequestParam int days) {
        List<Double> trendData = cryptoService.getCryptoHistoricalData(cryptoId, days);

        if (trendData == null || trendData.isEmpty()) {
            return ResponseEntity.status(500).body("Failed to fetch trend data");
        }

        return ResponseEntity.ok(trendData);
    }

    @GetMapping("/price")
    public ResponseEntity<?> getCryptoPrice(@RequestParam String cryptoId) {
        double price = cryptoService.getCryptoPrice(cryptoId);
        if (price < 0) {
            return ResponseEntity.status(404).body("Price not found for crypto ID: " + cryptoId);
        }
        return ResponseEntity.ok("{ \"cryptoId\": \"" + cryptoId + "\", \"price\": " + price + " }");
    }

    @PostMapping("/buy")
    public ResponseEntity<?> buyCrypto(@RequestBody BuyRequest buyRequest) {
        String email = buyRequest.getEmail();
        String cryptoId = buyRequest.getCryptoId();
        double usdAmount = buyRequest.getUsdAmount();
    
        // Fetch user
        Person person = personRepository.findByEmail(email);
        if (person == null) {
            return ResponseEntity.badRequest().body("User not found");
        }
    
        // Fetch live cryptocurrency data
        Crypto[] liveData = cryptoService.getCryptoData();
        if (liveData == null || liveData.length == 0) {
            return ResponseEntity.status(500).body("Failed to fetch live cryptocurrency data.");
        }
    
        // Validate the cryptoId against live data
        Crypto selectedCrypto = null;
        for (Crypto crypto : liveData) {
            if (crypto.getSymbol().equalsIgnoreCase(cryptoId) || crypto.getName().equalsIgnoreCase(cryptoId)) {
                selectedCrypto = crypto;
                break;
            }
        }
    
        if (selectedCrypto == null) {
            return ResponseEntity.badRequest().body("Invalid cryptocurrency ID or name.");
        }
    
        // Calculate crypto amount
        double cryptoPrice = selectedCrypto.getPrice();
        double cryptoAmount = usdAmount / cryptoPrice;
    
        // Check for sufficient balance
        if (person.getBalanceDouble() < usdAmount) {
            return ResponseEntity.badRequest().body("Insufficient balance.");
        }
    
        // Deduct balance and update user's holdings
        double updatedBalance = person.getBalanceDouble() - usdAmount;
        person.setBalanceString(updatedBalance, "crypto");
    
        userStocksTable userStocks = person.getUser_stocks();
        if (userStocks == null) {
            userStocks = new userStocksTable("", selectedCrypto.getSymbol() + ":" + cryptoAmount, String.valueOf(updatedBalance), person.getEmail(), person, false, true, "");
        } else {
            String updatedCrypto = addOrUpdateCryptoHoldings(userStocks.getCrypto(), selectedCrypto.getSymbol(), cryptoAmount);
            userStocks.setCrypto(updatedCrypto);
            userStocks.setBalance(String.valueOf(updatedBalance));
    
            // ✅ **Update transaction history**
        SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd-yyyy HH:mm");
        String formattedDate = dateFormat.format(new Date());
        String transactionEntry = "Bought " + cryptoAmount + " " + selectedCrypto.getSymbol() + " for $" + usdAmount + " at " + formattedDate;
        userStocks.setCryptoHistory(userStocks.getCryptoHistory() + transactionEntry + "\n");
        }
    
        // Save to database
        userStocksRepo.save(userStocks);
        personRepository.save(person);
    
        return ResponseEntity.ok("Successfully purchased " + cryptoAmount + " of " + selectedCrypto.getSymbol() + " for $" + usdAmount);
    }
    
    @GetMapping("/search")
    public ResponseEntity<?> searchCrypto(@RequestParam String cryptoId) {
        // Fetch live cryptocurrency data
        Crypto[] liveData = cryptoService.getCryptoData();
        if (liveData == null || liveData.length == 0) {
            return ResponseEntity.status(500).body("Failed to fetch live cryptocurrency data.");
        }
        // Search for the requested cryptocurrency by symbol or name
        Crypto selectedCrypto = null;
        for (Crypto crypto : liveData) {
            if (crypto.getSymbol().equalsIgnoreCase(cryptoId) || crypto.getName().equalsIgnoreCase(cryptoId)) {
                selectedCrypto = crypto;
                break;
            }
        }
        // Return error if crypto is not found
        if (selectedCrypto == null) {
            return ResponseEntity.status(404).body("Cryptocurrency not found: " + cryptoId);
        }
        // Return the cryptocurrency details
        return ResponseEntity.ok(selectedCrypto);
    }

    @GetMapping("/holdings")
    public ResponseEntity<?> getUserHoldings(@RequestParam String email) {
        userStocksTable userStocks = userStocksRepo.findByEmail(email);

        if (userStocks == null || userStocks.getCrypto() == null || userStocks.getCrypto().isEmpty()) {
            return ResponseEntity.status(404).body("No crypto holdings found for email: " + email);
        }

        return ResponseEntity.ok("{ \"email\": \"" + email + "\", \"holdings\": \"" + userStocks.getCrypto().replace("\n", "\\n") + "\" }");
    }

    

    @PostMapping("/sell")
    public ResponseEntity<?> sellCrypto(@RequestBody SellRequest sellRequest) {
        String email = sellRequest.getEmail();
        String cryptoId = sellRequest.getCryptoId();
        double cryptoAmount = sellRequest.getCryptoAmount();
    
        // Fetch user
        Person person = personRepository.findByEmail(email);
        if (person == null) {
            return ResponseEntity.badRequest().body("User not found");
        }
    
        // Fetch live cryptocurrency data
        Crypto[] liveData = cryptoService.getCryptoData();
        if (liveData == null || liveData.length == 0) {
            return ResponseEntity.status(500).body("Failed to fetch live cryptocurrency data.");
        }
    
        // Validate the cryptoId against live data
        Crypto selectedCrypto = null;
        for (Crypto crypto : liveData) {
            if (crypto.getSymbol().equalsIgnoreCase(cryptoId) || crypto.getName().equalsIgnoreCase(cryptoId)) {
                selectedCrypto = crypto;
                break;
            }
        }
    
        if (selectedCrypto == null) {
            return ResponseEntity.badRequest().body("Invalid cryptocurrency ID or name.");
        }
    
        // Get the current price of the cryptocurrency
        double cryptoPrice = selectedCrypto.getPrice();
    
        // Fetch and update crypto holdings
        userStocksTable userStocks = person.getUser_stocks();
        if (userStocks == null || userStocks.getCrypto() == null || userStocks.getCrypto().isEmpty()) {
            return ResponseEntity.badRequest().body("No crypto holdings found to sell.");
        }
    
        String updatedCrypto = removeOrUpdateCryptoHoldings(userStocks.getCrypto(), selectedCrypto.getSymbol(), cryptoAmount);
        if (updatedCrypto == null) {
            return ResponseEntity.badRequest().body("Insufficient crypto to sell.");
        }
    
        // Update balance
        double totalValueSold = cryptoPrice * cryptoAmount;
        double updatedBalance = person.getBalanceDouble() + totalValueSold;
        person.setBalanceString(updatedBalance, "crypto");
        userStocks.setCrypto(updatedCrypto);
        userStocks.setBalance(String.valueOf(updatedBalance));
    
        // ✅ **Update transaction history**
        SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd-yyyy HH:mm");
        String formattedDate = dateFormat.format(new Date());
        String transactionEntry = "Sold " + cryptoAmount + " " + selectedCrypto.getSymbol() + " for $" + totalValueSold + " at " + formattedDate;
        userStocks.setCryptoHistory(userStocks.getCryptoHistory() + transactionEntry + "\n");
    
        // Save to database
        userStocksRepo.save(userStocks);
        personRepository.save(person);
    
        return ResponseEntity.ok("Successfully sold " + cryptoAmount + " of " + selectedCrypto.getSymbol() + " for $" + totalValueSold);
    }
    @GetMapping("/history")
    public ResponseEntity<?> getCryptoTransactionHistory(@RequestParam String email) {
    userStocksTable userStocks = userStocksRepo.findByEmail(email);
    
    if (userStocks == null || userStocks.getCryptoHistory() == null || userStocks.getCryptoHistory().isEmpty()) {
        return ResponseEntity.status(404).body("No transaction history found for email: " + email);
    }
    
    return ResponseEntity.ok("{ \"email\": \"" + email + "\", \"cryptoHistory\": \"" + userStocks.getCryptoHistory().replace("\n", "\\n") + "\" }");
}

    // Utility method to resolve crypto ID to ticker symbol
    private String resolveCryptoId(String cryptoId) {
        Crypto crypto = cryptoJPArepo.findBySymbol(cryptoId);
        if (crypto == null) {
            crypto = cryptoJPArepo.findByNameIgnoreCase(cryptoId);
        }
        return crypto != null ? crypto.getSymbol() : null;
    }

    private String addOrUpdateCryptoHoldings(String currentCrypto, String cryptoId, double cryptoAmount) {
        StringBuilder updatedCrypto = new StringBuilder();
        boolean updated = false;

        if (currentCrypto != null && !currentCrypto.isEmpty()) {
            String[] holdings = currentCrypto.split(",");
            for (String holding : holdings) {
                if (holding.isEmpty()) continue;

                String[] parts = holding.split(":");
                if (parts.length != 2) continue;

                String id = parts[0];
                double amount = Double.parseDouble(parts[1]);

                if (id.equalsIgnoreCase(cryptoId)) {
                    amount += cryptoAmount;
                    updated = true;
                }
                updatedCrypto.append(id).append(":").append(amount).append(",");
            }
        }

        if (!updated) {
            updatedCrypto.append(cryptoId).append(":").append(cryptoAmount).append(",");
        }

        return updatedCrypto.toString().replaceAll(",$", "");
    }

    private String removeOrUpdateCryptoHoldings(String currentCrypto, String cryptoId, double cryptoAmount) {
        StringBuilder updatedCrypto = new StringBuilder();
        boolean removed = false;
    
        if (currentCrypto != null && !currentCrypto.trim().isEmpty()) {
            String[] holdings = currentCrypto.split(",");
            for (String holding : holdings) {
                if (holding == null || holding.trim().isEmpty()) continue;
    
                String[] parts = holding.split(":");
                if (parts.length != 2) continue;
    
                String id = parts[0].trim();
                double amount = Double.parseDouble(parts[1].trim());
    
                // Reduce the crypto amount if the ID matches
                if (id.equalsIgnoreCase(cryptoId)) {
                    if (amount < cryptoAmount) {
                        return null; // Insufficient balance to sell
                    }
                    amount -= cryptoAmount;
                    removed = true;
                }
    
                // Append only non-zero amounts
                if (amount > 0) {
                    updatedCrypto.append(id).append(":").append(amount).append(",");
                }
            }
        }
    
        if (!removed) {
            return null; // Crypto ID not found in holdings
        }
    
        // Remove trailing comma and return
        return updatedCrypto.toString().replaceAll(",$", "");
    }
    

    // Inner DTO class for BuyRequest
    static class BuyRequest {
        private String email;
        private String cryptoId;
        private double usdAmount;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getCryptoId() { return cryptoId; }
        public void setCryptoId(String cryptoId) { this.cryptoId = cryptoId; }
        public double getUsdAmount() { return usdAmount; }
        public void setUsdAmount(double usdAmount) { this.usdAmount = usdAmount; }
    }

    // Inner DTO class for SellRequest
    static class SellRequest {
        private String email;
        private String cryptoId;
        private double cryptoAmount;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getCryptoId() { return cryptoId; }
        public void setCryptoId(String cryptoId) { this.cryptoId = cryptoId; }
        public double getCryptoAmount() { return cryptoAmount; }
        public void setCryptoAmount(double cryptoAmount) { this.cryptoAmount = cryptoAmount; }
    }
}
