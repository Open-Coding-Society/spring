package com.nighthawk.spring_portfolio.mvc.bathroom;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.nighthawk.spring_portfolio.mvc.person.Person;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class Tinkle {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @OneToOne
    @JoinColumn(name = "uid")
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JsonBackReference
    private Person person;

    private String timeIn; // Stores comma-separated time pairs

    @Column
    @Convert(converter = TimeInOutPairsConverter.class)
    private List<LocalDateTime[]> timeInOutPairs = new ArrayList<>();

    @Column
    private String personName;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Tinkle(Person person, String statsInput) {
        this.person = person;
        this.personName = person.getName();
        this.timeIn = statsInput;
        parseAndStoreTimeInOut(statsInput);
    }

    public void addTimeIn(String timeInOutPairs) {
        if (timeInOutPairs != null && !timeInOutPairs.isEmpty()) {
            if (this.timeInOutPairs == null || this.timeInOutPairs.isEmpty()) {
                this.timeInOutPairs = new ArrayList<>();
            } else {
                this.timeInOutPairs = new ArrayList<>(this.timeInOutPairs);
            }

            String[] pairs = timeInOutPairs.split(",");
            DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            for (String pair : pairs) {
                String[] times = pair.split("-");
                if (times.length == 2) {
                    try {
                        times[0] = formatTime(times[0], timeFormatter);
                        times[1] = formatTime(times[1], timeFormatter);

                        String date = LocalDateTime.now().toLocalDate().toString();
                        LocalDateTime parsedTimeIn = LocalDateTime.parse(date + " " + times[0], dateTimeFormatter);
                        LocalDateTime parsedTimeOut = LocalDateTime.parse(date + " " + times[1], dateTimeFormatter);

                        this.timeInOutPairs.add(new LocalDateTime[]{parsedTimeIn, parsedTimeOut});

                        // Update timeIn column to maintain consistency
                        if (this.timeIn == null || this.timeIn.isEmpty()) {
                            this.timeIn = date + " " + times[0] + "--" + date + " " + times[1];
                        } else {
                            this.timeIn += "," + date + " " + times[0] + "--" + date + " " + times[1];
                        }
                    } catch (Exception e) {
                        System.out.println("⚠️ Failed to parse time: " + pair);
                    }
                }
            }
        }
    }

    private String formatTime(String time, DateTimeFormatter formatter) {
        String[] parts = time.split(":");
        if (parts.length == 3) {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            int second = Integer.parseInt(parts[2]);
            return String.format("%02d:%02d:%02d", hour, minute, second);
        }
        return time;
    }

    public void addTimeIn(LocalDateTime timeIn, LocalDateTime timeOut) {
        this.timeInOutPairs.add(new LocalDateTime[]{timeIn, timeOut});

        String formattedPair = timeIn.format(formatter) + "--" + timeOut.format(formatter);
        if (this.timeIn == null || this.timeIn.isEmpty()) {
            this.timeIn = formattedPair;
        } else {
            this.timeIn += "," + formattedPair;
        }
    }

    private void parseAndStoreTimeInOut(String statsInput) {
        if (statsInput != null && !statsInput.isEmpty()) {
            String[] pairs = statsInput.split(",");
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            for (String pair : pairs) {
                String[] times = pair.split("--");
                if (times.length == 2) {
                    try {
                        LocalDateTime parsedTimeIn = LocalDateTime.parse(times[0], dateTimeFormatter);
                        LocalDateTime parsedTimeOut = LocalDateTime.parse(times[1], dateTimeFormatter);
                        this.timeInOutPairs.add(new LocalDateTime[]{parsedTimeIn, parsedTimeOut});
                    } catch (Exception e) {
                        System.out.println("⚠️ Failed to parse existing time entry: " + pair);
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return "Tinkle{" +
                "id=" + id +
                ", personName='" + personName + '\'' +
                ", timeInOutPairs size=" + (timeInOutPairs != null ? timeInOutPairs.size() : 0) +
                '}';
    }

    public static Tinkle[] init(Person[] persons) {
        ArrayList<Tinkle> tinkles = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
        java.util.Random random = new java.util.Random();
    
        String[] baseDates = {
            "2025-04-07", "2025-04-08", "2025-04-09", "2025-04-10", "2025-04-11",
            "2025-04-14", "2025-04-15", "2025-04-16", "2025-04-17", "2025-04-18"
        };
    
        String[] timeInOutSamples = new String[10];
    
        for (int i = 0; i < 10; i++) {
            StringBuilder sampleBuilder = new StringBuilder();
            String date = baseDates[i % baseDates.length];
    
            // Assign a "base" average trip duration for this person (10-20 min)
            int baseDuration = 10 + random.nextInt(11); // 10 to 20 minutes
    
            for (int j = 0; j < 20; j++) {
                int hour = 8 + (j % 10); // 8 AM to 5 PM
                int minute = (j * 3) % 60;
    
                // Add a random adjustment of ±3 minutes around base duration
                int durationAdjustment = random.nextInt(7) - 3;  // -3 to +3 minutes
                int tripDuration = Math.max(5, baseDuration + durationAdjustment); // Ensure at least 5 min trips
    
                String timeIn = String.format("%s %02d:%02d:00", date, hour, minute);
                String timeOut = LocalDateTime.parse(timeIn, formatter).plusMinutes(tripDuration).format(formatter);
    
                sampleBuilder.append(timeIn).append("--").append(timeOut);
                if (j != 19) sampleBuilder.append(","); // comma between entries
            }
    
            timeInOutSamples[i] = sampleBuilder.toString();
        }
    
        for (int i = 0; i < persons.length; i++) {
            String timeInOut = timeInOutSamples[i % timeInOutSamples.length];
            tinkles.add(new Tinkle(persons[i], timeInOut));
        }
    
        return tinkles.toArray(new Tinkle[0]);
    }
    
    
}
