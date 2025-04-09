package com.nighthawk.spring_portfolio.mvc.cryptoMining;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;
import com.nighthawk.spring_portfolio.mvc.userStocks.UserStocksRepository;
import com.nighthawk.spring_portfolio.mvc.userStocks.userStocksTable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;
import com.nighthawk.spring_portfolio.mvc.userStocks.UserStocksRepository;
import com.nighthawk.spring_portfolio.mvc.userStocks.userStocksTable;

import java.util.stream.Collectors;
import java.text.SimpleDateFormat;


@RestController
@RequestMapping("/api/mining")
@Transactional
@CrossOrigin(origins = {"http://localhost:4100", "http://localhost:8084"})  // Enable CORS for frontend URLs
public class MiningController {
    @Autowired
    private PersonJpaRepository personRepository;
    
    @Autowired
    private MiningUserRepository miningUserRepository;
    
    @Autowired
    private GPURepository gpuRepository;

    @Autowired
    private MiningService miningService;

    private GPU getRandomBudgetGPU() {
        List<GPU> budgetGPUs = gpuRepository.findAll().stream()
            .filter(gpu -> gpu.getCategory().equals("Budget GPUs ($10000-20000)"))
            .collect(Collectors.toList());
        
        if (budgetGPUs.isEmpty()) {
            throw new RuntimeException("No budget GPUs found");
        }
        
        int randomIndex = (int) (Math.random() * budgetGPUs.size());
        return budgetGPUs.get(randomIndex);
    }

    private MiningUser getOrCreateMiningUser() {
        try {
            // Get authentication details
            var auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) {
                throw new RuntimeException("No authentication context found");
            }

            String uid = auth.getName();

            if ("anonymousUser".equals(uid)) {
                throw new RuntimeException("User not authenticated");
            }

            // Find person by UID with detailed logging
            Person person = personRepository.findByUid(uid);
            
            if (person == null) {
                throw new RuntimeException("Person not found for UID: " + uid);
            }        

            // Find or create mining user with detailed logging
            return miningUserRepository.findByPerson(person)
                .map(existingUser -> {
                    return existingUser;
                })
                .orElseGet(() -> {
                    MiningUser newUser = new MiningUser(person);
                    
                    // Give new user a random budget GPU
                    GPU randomBudgetGPU = getRandomBudgetGPU();
                    newUser.addGPU(randomBudgetGPU);
                    
                    MiningUser savedUser = miningUserRepository.save(newUser);
                    return savedUser;
                });

        } catch (Exception e) {
            throw new RuntimeException("Failed to get or create mining user: " + e.getMessage(), e);
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getMiningStats() {
        try {
            MiningUser user = getOrCreateMiningUser();
            Map<String, Object> stats = new HashMap<>();
            
            // Group GPUs by ID and count quantities
            Map<Long, Map<String, Object>> gpuGroups = new HashMap<>();
            for (GPU gpu : user.getOwnedGPUs()) {
                Long gpuId = gpu.getId();
                if (!gpuGroups.containsKey(gpuId)) {
                    Map<String, Object> gpuInfo = new HashMap<>();
                    gpuInfo.put("id", gpuId);
                    gpuInfo.put("name", gpu.getName());
                    gpuInfo.put("hashRate", gpu.getHashRate());
                    gpuInfo.put("powerConsumption", gpu.getPowerConsumption());
                    gpuInfo.put("temp", gpu.getTemp());
                    gpuInfo.put("price", gpu.getPrice());
                    gpuInfo.put("quantity", user.getGpuQuantity(gpuId));
                    gpuInfo.put("isActive", user.getActiveGPUs().contains(gpu));
                    gpuGroups.put(gpuId, gpuInfo);
                }
            }
            
            stats.put("gpus", new ArrayList<>(gpuGroups.values()));
            stats.put("btcBalance", String.format("%.8f", user.getBtcBalance()));
            stats.put("pendingBalance", String.format("%.8f", user.getPendingBalance()));
            stats.put("totalBalanceUSD", String.format("%.2f", (user.getPendingBalance() + user.getBtcBalance()) * MiningService.BTC_PRICE));
            stats.put("hashrate", String.format("%.2f", user.getCurrentHashrate()));
            stats.put("shares", user.getShares());
            stats.put("isMining", user.isMining());
            stats.put("currentPool", user.getCurrentPool());
            stats.put("powerConsumption", user.getPowerConsumption());
            stats.put("averageTemperature", user.getAverageTemperature());
            stats.put("dailyRevenue", user.getDailyRevenue());
            stats.put("powerCost", user.getPowerCost());
            stats.put("activeGPUsCount", user.getActiveGPUs().size());

            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/shop")
    public ResponseEntity<?> getGPUShop() {
        try {
            MiningUser user = getOrCreateMiningUser();
            List<GPU> allGpus = gpuRepository.findAll();
            
            // Convert GPUs to maps with additional ownership info
            List<Map<String, Object>> gpuList = new ArrayList<>();
            for (GPU gpu : allGpus) {
                Map<String, Object> gpuInfo = new HashMap<>();
                gpuInfo.put("id", gpu.getId());
                gpuInfo.put("name", gpu.getName());
                gpuInfo.put("hashRate", gpu.getHashRate());
                gpuInfo.put("powerConsumption", gpu.getPowerConsumption());
                gpuInfo.put("temp", gpu.getTemp());
                gpuInfo.put("price", gpu.getPrice());
                gpuInfo.put("category", gpu.getCategory());
                gpuInfo.put("owned", user.ownsGPUById(gpu.getId()));
                gpuInfo.put("quantity", user.getGpuQuantity(gpu.getId()));
                
                gpuList.add(gpuInfo);
            }
            
            return ResponseEntity.ok(gpuList);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/gpu/buy/{gpuId}")
    public ResponseEntity<?> buyGPU(@PathVariable Long gpuId, @RequestBody(required = false) Map<String, Integer> request) {
        try {
            MiningUser user = getOrCreateMiningUser();
            
            GPU gpu = gpuRepository.findById(gpuId)
                .orElseThrow(() -> new RuntimeException("GPU not found"));
            
            // Get quantity from request, default to 1 if not specified
            int quantity = (request != null && request.containsKey("quantity")) ? request.get("quantity") : 1;
            
            // Get user's crypto balance
            Person person = user.getPerson();
            double currentBalance = person.getBalanceDouble();
            
            if (currentBalance < gpu.getPrice() * quantity) {
                return ResponseEntity.badRequest()
                    .body(Map.of(
                        "success", false,
                        "message", "Insufficient balance to buy these GPUs"
                    ));
            }

            // Deduct total GPU price from balance
            double newBalance = currentBalance - gpu.getPrice() * quantity;
            person.setBalanceString(newBalance);
            personRepository.save(person);
            
            // Add GPUs to user's inventory
            for (int i = 0; i < quantity; i++) {
                user.addGPU(gpu);
            }
            
            // Save the updated user
            miningUserRepository.save(user);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", String.format("Successfully purchased %dx %s", quantity, gpu.getName()),
                "newBalance", String.format("%.2f", newBalance)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/toggle")
    public ResponseEntity<?> toggleMining() {
        try {
            MiningUser user = getOrCreateMiningUser();
            user.setMining(!user.isMining());
            miningUserRepository.save(user);
            
            Map<String, Object> response = new HashMap<>();
            response.put("isMining", user.isMining());
            response.put("message", user.isMining() ? "Mining started" : "Mining stopped");
            response.put("currentHashrate", user.getCurrentHashrate());
            response.put("activeGPUs", user.getActiveGPUs().size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/gpu/toggle/{gpuId}")
    public ResponseEntity<?> toggleGPU(@PathVariable Long gpuId) {
        try {
            MiningUser user = getOrCreateMiningUser();
            GPU gpu = gpuRepository.findById(gpuId)
                .orElseThrow(() -> new RuntimeException("GPU not found"));
    
            // Check if user owns this GPU
            if (!user.ownsGPUById(gpuId)) {
                return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "You don't own this GPU"));
            }
    
            boolean isActive = user.toggleGPU(gpu);
            miningUserRepository.save(user);
    
            return ResponseEntity.ok(Map.of("success", true, "message", isActive ? "GPU activated" : "GPU deactivated", "isActive", isActive));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/state")
    public ResponseEntity<?> getMiningState() {
        try {
            MiningUser user = getOrCreateMiningUser();
            if (user == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "User not authenticated"));
            }
            
            // Calculate profitability
            try {
                miningService.calculateProfitability(user);
            } catch (Exception e) {
                // Silently handle error
            }

            // Create response
            Map<String, Object> stats = new HashMap<>();
            try {
                double pendingBTC = user.getPendingBalance();
                double confirmedBTC = user.getBtcBalance();
                
                // Add all stats...
                stats.put("btcBalance", String.format("%.8f", confirmedBTC));
                stats.put("btcBalanceUSD", String.format("%.2f", confirmedBTC * MiningService.BTC_PRICE));
                stats.put("pendingBalance", String.format("%.8f", pendingBTC));
                stats.put("pendingBalanceUSD", String.format("%.2f", pendingBTC * MiningService.BTC_PRICE));
                stats.put("totalBalanceUSD", String.format("%.2f", (pendingBTC + confirmedBTC) * MiningService.BTC_PRICE));
                
                // Mining status
                stats.put("hashrate", String.format("%.2f", user.getCurrentHashrate()));
                stats.put("shares", user.getShares());
                stats.put("isMining", user.isMining());
                stats.put("currentPool", user.getCurrentPool());
                
                // Power and temperature
                stats.put("powerConsumption", user.getPowerConsumption());
                stats.put("averageTemperature", user.getAverageTemperature());
                
                // Profitability
                stats.put("dailyRevenueBTC", String.format("%.8f", user.getDailyRevenue() / MiningService.BTC_PRICE));
                stats.put("dailyRevenueUSD", String.format("%.2f", user.getDailyRevenue()));
                stats.put("powerCost", user.getPowerCost());
                stats.put("netProfitUSD", String.format("%.2f", user.getDailyRevenue() - user.getPowerCost()));

                // GPU information - Group by ID
                Map<Long, Map<String, Object>> gpuGroups = new HashMap<>();
                for (GPU gpu : user.getOwnedGPUs()) {
                    Long gpuId = gpu.getId();
                    if (!gpuGroups.containsKey(gpuId)) {
                        Map<String, Object> gpuInfo = new HashMap<>();
                        gpuInfo.put("id", gpuId);
                        gpuInfo.put("name", gpu.getName());
                        gpuInfo.put("hashRate", gpu.getHashRate());
                        gpuInfo.put("powerConsumption", gpu.getPowerConsumption());
                        gpuInfo.put("temp", gpu.getTemp());
                        gpuInfo.put("price", gpu.getPrice());
                        gpuInfo.put("quantity", user.getGpuQuantity(gpuId));
                        gpuInfo.put("isActive", user.getActiveGPUs().contains(gpu));
                        gpuGroups.put(gpuId, gpuInfo);
                    }
                }
                
                stats.put("gpus", new ArrayList<>(gpuGroups.values()));

                // Group active GPUs by ID
                Map<Long, Map<String, Object>> activeGpuGroups = new HashMap<>();
                for (GPU gpu : user.getActiveGPUs()) {
                    Long gpuId = gpu.getId();
                    if (!activeGpuGroups.containsKey(gpuId)) {
                        Map<String, Object> gpuInfo = new HashMap<>();
                        gpuInfo.put("id", gpuId);
                        gpuInfo.put("name", gpu.getName());
                        gpuInfo.put("hashRate", gpu.getHashRate());
                        gpuInfo.put("powerConsumption", gpu.getPowerConsumption());
                        gpuInfo.put("temp", gpu.getTemp());
                        gpuInfo.put("price", gpu.getPrice());
                        gpuInfo.put("category", gpu.getCategory());
                        gpuInfo.put("available", gpu.isAvailable());
                        gpuInfo.put("efficiency", gpu.getEfficiency());
                        gpuInfo.put("quantity", user.getGpuQuantity(gpuId));
                        activeGpuGroups.put(gpuId, gpuInfo);
                    }
                }
                
                stats.put("activeGPUs", new ArrayList<>(activeGpuGroups.values()));

                return ResponseEntity.ok(stats);
                
            } catch (Exception e) {
                throw e;
            }

        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to get mining state");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("type", e.getClass().getName());
            errorResponse.put("timestamp", new Date().toString());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }

    @PostMapping("/testMining")
    public ResponseEntity<?> testMining() {
        try {
            MiningUser user = getOrCreateMiningUser(); // Get or create the mining user
            user.setMining(true); // Ensure the user is set to mining

            // Simulate a hashrate for testing
            user.setCurrentHashrate(60.55); // Set hashrate to 60.55 MH/s

            // Call the mining process
            miningService.processMining(); // This should update the user's balance and shares

            // Prepare the response
            Map<String, Object> response = new HashMap<>();
            response.put("btcBalance", user.getBtcBalance());
            response.put("pendingBalance", user.getPendingBalance());
            response.put("shares", user.getShares());
            response.put("message", "Mining test completed successfully.");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/mining-status")
    public ResponseEntity<?> getMiningStatus() {
        try {
            MiningUser user = getOrCreateMiningUser();
            
            // Get user's crypto balance
            Person person = user.getPerson();
            double currentBalance = person.getBalanceDouble();
            
            Map<String, Object> status = new HashMap<>();
            double pendingBTC = user.getPendingBalance();
            double confirmedBTC = user.getBtcBalance();
            
            // Calculate USD values using current balance
            double btcPrice = MiningService.BTC_PRICE;
            double pendingUSD = pendingBTC * btcPrice;
            double confirmedUSD = confirmedBTC * btcPrice;
            double totalUSD = pendingUSD + confirmedUSD;

            status.put("pendingBalance", String.format("%.8f", pendingBTC));
            status.put("pendingBalanceUSD", String.format("%.2f", pendingUSD));
            status.put("confirmedBalance", String.format("%.8f", confirmedBTC));
            status.put("confirmedBalanceUSD", String.format("%.2f", confirmedUSD));
            status.put("totalBalanceUSD", String.format("%.2f", totalUSD));
            status.put("userBalance", String.format("%.2f", currentBalance));
            status.put("isMining", user.isMining());
            status.put("activeGPUs", user.getActiveGPUs().size());
            status.put("currentHashrate", String.format("%.2f", user.getCurrentHashrate()));
            status.put("lastShare", user.getShares());
            status.put("estimated24hBTC", String.format("%.8f", user.getDailyRevenue() / MiningService.BTC_PRICE));
            status.put("estimated24hUSD", String.format("%.2f", user.getDailyRevenue()));
            
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/test-mining-system")
    public ResponseEntity<?> testMiningSystem() {
        try {
            MiningUser user = getOrCreateMiningUser();
            
            // Force mining on and ensure GPUs are active
            user.setMining(true);
            if (user.getActiveGPUs().isEmpty() && !user.getOwnedGPUs().isEmpty()) {
                // Activate the first GPU if none are active
                user.toggleGPU(user.getOwnedGPUs().get(0));
            }
            miningUserRepository.save(user);
            
            // Manually trigger mining process
            miningService.processMining();
            
            // Wait for one mining cycle
            Thread.sleep(MiningService.MINING_INTERVAL);
            
            // Refresh user data
            user = getOrCreateMiningUser();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("initialBalance", user.getBtcBalance());
            response.put("finalPending", user.getPendingBalance());
            response.put("hashrate", user.getCurrentHashrate());
            response.put("activeGPUs", user.getActiveGPUs().size());
            response.put("ownedGPUs", user.getOwnedGPUs().size());
            response.put("miningEnabled", user.isMining());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Mining test failed: " + e.getMessage()));
        }
    }

    @GetMapping("/debug-values")
    public ResponseEntity<?> debugValues() {
        try {
            MiningUser user = getOrCreateMiningUser();
            Map<String, Object> debug = new HashMap<>();
            
            // Raw values
            debug.put("rawPendingBTC", user.getPendingBalance());
            debug.put("rawConfirmedBTC", user.getBtcBalance());
            debug.put("btcPrice", MiningService.BTC_PRICE);
            
            // Calculated values
            debug.put("pendingUSD", user.getPendingBalance() * MiningService.BTC_PRICE);
            debug.put("confirmedUSD", user.getBtcBalance() * MiningService.BTC_PRICE);
            debug.put("totalUSD", (user.getPendingBalance() + user.getBtcBalance()) * MiningService.BTC_PRICE);
            
            // Formatted values
            debug.put("formattedPendingUSD", String.format("%.2f", user.getPendingBalance() * MiningService.BTC_PRICE));
            debug.put("formattedConfirmedUSD", String.format("%.2f", user.getBtcBalance() * MiningService.BTC_PRICE));
            debug.put("formattedTotalUSD", String.format("%.2f", (user.getPendingBalance() + user.getBtcBalance()) * MiningService.BTC_PRICE));
            
            return ResponseEntity.ok(debug);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/gpu/sell/{gpuId}")
    public ResponseEntity<?> sellGPU(@PathVariable Long gpuId, @RequestBody Map<String, Integer> request) {
        try {
            MiningUser user = getOrCreateMiningUser();
            GPU gpu = gpuRepository.findById(gpuId)
                .orElseThrow(() -> new RuntimeException("GPU not found"));

            int quantityToSell = request.getOrDefault("quantity", 1);
            if (quantityToSell <= 0) {
                return ResponseEntity.badRequest()
                    .body(Map.of(
                        "success", false,
                        "message", "Invalid quantity"
                    ));
            }

            // Check if user owns enough GPUs
            int currentQuantity = user.getGpuQuantity(gpuId);
            if (currentQuantity < quantityToSell) {
                return ResponseEntity.badRequest()
                    .body(Map.of(
                        "success", false,
                        "message", "Not enough GPUs to sell"
                    ));
            }

            // Calculate sell price (80% of original price)
            double sellPrice = gpu.getPrice() * 0.8 * quantityToSell;

            // Update user's balance using Person
            Person person = user.getPerson();
            double currentBalance = person.getBalanceDouble();
            person.setBalance(String.format("%.2f", currentBalance + sellPrice));
            personRepository.save(person);

            // Remove GPUs from user's inventory
            user.removeGPUs(gpu, quantityToSell);
            miningUserRepository.save(user);

            // Stop mining if no GPUs left
            if (user.getOwnedGPUs().isEmpty()) {
                user.setMining(false);
                miningUserRepository.save(user);
            }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", String.format("Successfully sold %d %s for $%.2f", 
                    quantityToSell, gpu.getName(), sellPrice)
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "success", false,
                    "message", e.getMessage()
                ));
        }
    }

    @PostMapping("/gpu/sell/batch")
    public ResponseEntity<?> sellGPUsBatch(@RequestBody Map<String, List<Map<String, Object>>> request) {
        try {
            MiningUser user = getOrCreateMiningUser();
            List<Map<String, Object>> gpus = request.get("gpus");
            
            if (gpus == null || gpus.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("success", false, 
                               "message", "No GPUs selected"));
            }

            double totalSellPrice = 0.0;
            List<String> soldGPUs = new ArrayList<>();

            // Process each GPU sale
            for (Map<String, Object> gpuInfo : gpus) {
                Long gpuId = Long.parseLong(gpuInfo.get("id").toString());
                int quantity = Integer.parseInt(gpuInfo.get("quantity").toString());

                GPU gpu = gpuRepository.findById(gpuId)
                    .orElseThrow(() -> new RuntimeException("GPU not found: " + gpuId));

                if (user.getGpuQuantity(gpuId) < quantity) {
                    continue; // Skip if not enough quantity
                }

                double sellPrice = gpu.getPrice() * 0.8 * quantity;
                totalSellPrice += sellPrice;
                user.removeGPUs(gpu, quantity);
                soldGPUs.add(String.format("%dx %s", quantity, gpu.getName()));
            }

            // Update user's balance using Person with source
            Person person = user.getPerson();
            double currentBalance = person.getBalanceDouble();
            double newBalance = currentBalance + totalSellPrice;
            person.setBalanceString(newBalance);
            personRepository.save(person);

            // Save changes
            miningUserRepository.save(user);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", String.format("Successfully sold %s for $%.2f", 
                                       String.join(", ", soldGPUs), totalSellPrice),
                "newBalance", String.format("%.2f", newBalance)
            ));

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/test-new-user-gpu")
    public ResponseEntity<?> testNewUserGPU() {
        try {
            // Get all budget GPUs first to verify they exist
            List<GPU> allBudgetGPUs = gpuRepository.findAll().stream()
                .filter(gpu -> gpu.getCategory().equals("Budget GPUs ($10000-20000)"))
                .collect(Collectors.toList());
            
            if (allBudgetGPUs.isEmpty()) {
                throw new RuntimeException("No budget GPUs found in the database. Make sure DataInitializer has run.");
            }

            // Get random GPU
            GPU randomBudgetGPU = allBudgetGPUs.get((int) (Math.random() * allBudgetGPUs.size()));

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Test completed successfully");
            response.put("totalBudgetGPUs", allBudgetGPUs.size());
            response.put("selectedGPU", Map.of(
                "id", randomBudgetGPU.getId(),
                "name", randomBudgetGPU.getName(),
                "hashRate", randomBudgetGPU.getHashRate(),
                "price", randomBudgetGPU.getPrice(),
                "category", randomBudgetGPU.getCategory()
            ));
            response.put("allBudgetGPUs", allBudgetGPUs.stream()
                .map(gpu -> Map.of(
                    "id", gpu.getId(),
                    "name", gpu.getName(),
                    "hashRate", gpu.getHashRate(),
                    "price", gpu.getPrice(),
                    "category", gpu.getCategory()
                ))
                .collect(Collectors.toList())
            );

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                    "error", e.getMessage(),
                    "errorType", e.getClass().getSimpleName(),
                    "details", "Check server logs for more information"
                ));
        }
    }

    @GetMapping("/cryptocurrencies")
    public ResponseEntity<?> getCryptocurrencies() {
        try {
            List<Map<String, Object>> cryptos = miningService.getAvailableCryptocurrencies();
            return ResponseEntity.ok(cryptos);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/crypto/select/{symbol}")
    public ResponseEntity<?> selectCryptocurrency(@PathVariable String symbol) {
        try {
            MiningUser user = getOrCreateMiningUser();
            Map<String, Object> result = miningService.changeMiningCrypto(user, symbol);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/balances")
    public ResponseEntity<?> getCryptoBalances() {
        try {
            MiningUser user = getOrCreateMiningUser();
            
            // Gather all crypto balances
            List<Map<String, Object>> balances = new ArrayList<>();
            
            for (CryptoBalance balance : user.getCryptoBalances()) {
                Cryptocurrency crypto = balance.getCryptocurrency();
                
                Map<String, Object> balanceInfo = new HashMap<>();
                balanceInfo.put("name", crypto.getName());
                balanceInfo.put("symbol", crypto.getSymbol());
                balanceInfo.put("logoUrl", crypto.getLogoUrl());
                balanceInfo.put("price", crypto.getPrice());
                balanceInfo.put("confirmedBalance", String.format("%.8f", balance.getConfirmedBalance()));
                balanceInfo.put("pendingBalance", String.format("%.8f", balance.getPendingBalance()));
                balanceInfo.put("confirmedUSD", String.format("%.2f", balance.getConfirmedBalanceUSD()));
                balanceInfo.put("pendingUSD", String.format("%.2f", balance.getPendingBalanceUSD()));
                balanceInfo.put("totalUSD", String.format("%.2f", balance.getTotalBalanceUSD()));
                
                // Add pool details for this cryptocurrency
                balanceInfo.put("algorithm", crypto.getMiningAlgorithm());
                balanceInfo.put("difficulty", crypto.getDifficulty());
                balanceInfo.put("minPayout", crypto.getMinPayout());
                balanceInfo.put("blockReward", crypto.getBlockReward());
                
                balances.add(balanceInfo);
            }
            
            // Include total USD value
            Map<String, Object> response = new HashMap<>();
            response.put("balances", balances);
            response.put("totalUSD", String.format("%.2f", user.getTotalCryptoValueUSD()));
            
            // Add current mining cryptocurrency
            if (user.getCurrentCryptocurrency() != null) {
                response.put("currentMining", user.getCurrentCryptocurrency().getSymbol());
            } else {
                response.put("currentMining", "BTC");
            }
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }
}
