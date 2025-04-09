package com.nighthawk.spring_portfolio.mvc.bathroom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;

@Controller
public class TinkleViewController {

    @Autowired
    private TinkleJPARepository tinkleRepository;

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private TinkleStatisticsService tinkleStatisticsService;

    @GetMapping("/admin/tinkle-view")
    public String showTinkleView(
        Model model, 
        @AuthenticationPrincipal UserDetails userDetails,
        RedirectAttributes redirectAttributes) {
        // Check if user is authenticated
        if (userDetails == null) {
            return "redirect:/login";
        }

        // Get the logged-in user's email
        String email = userDetails.getUsername();
        Person user = personRepository.findByUid(email);

        // Check if the user exists and has ROLE_ADMIN
        if (user == null || !user.hasRoleWithName("ROLE_ADMIN")) {
            return "redirect:/access-denied";
        }
        
        try {
            // Fetch all Tinkle records from the database
            List<Tinkle> tinkleList = tinkleRepository.findAll();
            
            // Add durations and format data for each entry
            List<Map<String, Object>> tinkleDataFormatted = new ArrayList<>();
            
            for (Tinkle tinkle : tinkleList) {
                Map<String, Object> row = new HashMap<>();
                
                // Handle null values
                String personName = tinkle.getPersonName() != null ? tinkle.getPersonName() : "Unknown";
                String timeIn = tinkle.getTimeIn() != null ? tinkle.getTimeIn() : "";
                
                // Basic data
                row.put("person_name", personName);
                
                // Format timeIn for display
                String formattedTimeIn = tinkleStatisticsService.formatTimeIn(timeIn);
                row.put("timeIn", formattedTimeIn);
                
                // Extract day from timeIn
                String day = tinkleStatisticsService.extractDay(timeIn);
                row.put("day", day);
                
                // Calculate and format duration
                String duration = tinkleStatisticsService.calculateDurationFormatted(timeIn);
                row.put("duration", duration);
                
                // Add formatted data 
                tinkleDataFormatted.add(row);
            }

            // Calculate average weekly durations
            Map<String, String> averageWeeklyDurations = tinkleStatisticsService.calculateAverageWeeklyDurationsFormatted(tinkleList);

            // Prepare data for chart (weekly durations per user)
            Map<String, Long> rawWeeklyDurations = tinkleStatisticsService.calculateAverageWeeklyDurations(tinkleList);
            
            // Add all data to the model
            model.addAttribute("tinkleList", tinkleDataFormatted);
            model.addAttribute("averageWeeklyDurations", averageWeeklyDurations);
            model.addAttribute("chartLabels", rawWeeklyDurations.keySet());
            model.addAttribute("chartData", rawWeeklyDurations.values());
            
            return "tinkle-view";
            
        } catch (Exception e) {
            // Log error
            e.printStackTrace();
            
            // Add error message
            redirectAttributes.addFlashAttribute("error", "An error occurred while loading tinkle data: " + e.getMessage());
            return "error";
        }
    }
}