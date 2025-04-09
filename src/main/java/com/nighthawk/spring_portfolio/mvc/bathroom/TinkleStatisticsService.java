package com.nighthawk.spring_portfolio.mvc.bathroom;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

@Service
public class TinkleStatisticsService {

    /**
     * Calculates the average weekly duration for each user.
     *
     * @param tinkleList List of Tinkle entries from the database.
     * @return A map where the key is the user's name and the value is their average weekly duration in seconds.
     */
    public Map<String, Long> calculateAverageWeeklyDurations(List<Tinkle> tinkleList) {
        // Group durations by person name
        Map<String, List<Long>> userWeeklyDurations = tinkleList.stream()
            .collect(Collectors.groupingBy(
                Tinkle::getPersonName,
                Collectors.mapping(
                    t -> calculateTotalDurationInSeconds(t.getTimeIn()),
                    Collectors.toList()
                )
            ));

        // Calculate the average weekly duration per user
        Map<String, Long> averageWeeklyDurations = new HashMap<>();
        for (Map.Entry<String, List<Long>> entry : userWeeklyDurations.entrySet()) {
            String userName = entry.getKey();
            List<Long> durations = entry.getValue();

            // Sum all durations and divide by the number of weeks (assuming 1 entry per week)
            long totalDuration = durations.stream().mapToLong(Long::longValue).sum();
            long averageDuration = durations.isEmpty() ? 0 : totalDuration / durations.size();
            averageWeeklyDurations.put(userName, averageDuration);
        }

        return averageWeeklyDurations;
    }

    /**
     * Calculates the total duration in seconds for a given timeIn string.
     *
     * @param timeIn A string containing time pairs (e.g., "08:00:00-08:10:00,10:30:00-10:45:00").
     * @return Total duration in seconds.
     */
    public long calculateTotalDurationInSeconds(String timeIn) {
        if (timeIn == null || timeIn.isEmpty()) return 0;
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        
        return Arrays.stream(timeIn.split(","))
            .mapToLong(pair -> {
                String[] times = pair.split("--"); // Changed delimiter to match new format
                if (times.length == 2) {
                    try {
                        LocalDateTime start = LocalDateTime.parse(times[0].trim(), formatter);
                        LocalDateTime end = LocalDateTime.parse(times[1].trim(), formatter);
                        return Duration.between(start, end).getSeconds();
                    } catch (Exception e) {
                        return 0; // skip malformed entries
                    }
                }
                return 0;
            })
            .sum();
    }

    public String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
    
    public Map<String, String> calculateAverageWeeklyDurationsFormatted(List<Tinkle> tinkleList) {
        Map<String, Long> averageWeeklyDurations = calculateAverageWeeklyDurations(tinkleList);
        return averageWeeklyDurations.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> formatDuration(entry.getValue())
            ));
    }
    
    public String calculateDurationFormatted(String timeIn) {
        long totalSeconds = calculateTotalDurationInSeconds(timeIn);
        return formatDuration(totalSeconds); // Reuse the formatDuration method
    }
    
    /**
     * Format timeIn string to display just HH:MM--HH:MM for each pair
     * @param timeIn The raw datetime pairs string
     * @return Formatted time pairs
     */
    public String formatTimeIn(String timeIn) {
        if (timeIn == null || timeIn.isEmpty()) return "No data";
        
        try {
            DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            
            return Arrays.stream(timeIn.split(","))
                .map(pair -> {
                    String[] times = pair.split("--");
                    if (times.length == 2) {
                        try {
                            LocalDateTime start = LocalDateTime.parse(times[0].trim(), inputFormatter);
                            LocalDateTime end = LocalDateTime.parse(times[1].trim(), inputFormatter);
                            return String.format("%02d:%02d--%02d:%02d", 
                                start.getHour(), start.getMinute(),
                                end.getHour(), end.getMinute());
                        } catch (Exception e) {
                            return "Invalid";
                        }
                    }
                    return "Invalid format";
                })
                .collect(Collectors.joining(", "));
        } catch (Exception e) {
            return "Format error";
        }
    }
    
    /**
     * Extract and format the day from timeIn string (MM-DD format)
     * @param timeIn The raw datetime pairs string
     * @return Day in MM-DD format
     */
    public String extractDay(String timeIn) {
        if (timeIn == null || timeIn.isEmpty()) return "No date";
        
        try {
            String[] times = timeIn.split("--");
            if (times.length >= 1) {
                String firstTime = times[0].trim();
                DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                LocalDateTime dateTime = LocalDateTime.parse(firstTime, inputFormatter);
                return String.format("%02d-%02d", dateTime.getMonthValue(), dateTime.getDayOfMonth());
            }
            return "Invalid";
        } catch (Exception e) {
            return "Date error";
        }
    }
    
    /**
     * Checks if a time range exceeds the threshold (for highlighting)
     * @param timeIn Single time range pair
     * @param thresholdMinutes Minutes threshold
     * @return true if time range exceeds threshold
     */
    public boolean isLongDuration(String timeInPair, int thresholdMinutes) {
        if (timeInPair == null || timeInPair.isEmpty()) return false;
        
        try {
            String[] times = timeInPair.split("--");
            if (times.length == 2) {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                LocalDateTime start = LocalDateTime.parse(times[0].trim(), formatter);
                LocalDateTime end = LocalDateTime.parse(times[1].trim(), formatter);
                long durationMinutes = Duration.between(start, end).toMinutes();
                return durationMinutes > thresholdMinutes;
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return false;
    }
}