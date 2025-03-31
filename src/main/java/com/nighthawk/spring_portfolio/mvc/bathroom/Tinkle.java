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
    @JoinColumn(name = "person_id")
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

        String[] timeInOutSamples = {
            "2025-02-17 08:45:00--2025-02-17 09:10:00,2025-02-17 10:15:00--2025-02-17 10:50:00",
            "2025-02-18 09:05:00--2025-02-18 09:25:00,2025-02-18 11:40:00--2025-02-18 12:10:00",
            "2025-02-19 11:35:00--2025-02-19 12:00:00,2025-02-19 13:10:00--2025-02-19 13:55:00",
            "2025-02-20 08:50:00--2025-02-20 09:05:00,2025-02-20 14:15:00--2025-02-20 14:45:00",
            "2025-02-21 12:10:00--2025-02-21 12:50:00,2025-02-21 15:20:00--2025-02-21 15:35:00",
            "2025-02-17 10:35:00--2025-02-17 11:10:00",
            "2025-02-18 08:55:00--2025-02-18 09:40:00,2025-02-18 14:25:00--2025-02-18 14:50:00",
            "2025-02-19 09:45:00--2025-02-19 10:05:00,2025-02-19 12:30:00--2025-02-19 13:05:00",
            "2025-02-20 13:05:00--2025-02-20 13:50:00,2025-02-20 15:10:00--2025-02-20 15:35:00",
            "2025-02-21 08:35:00--2025-02-21 09:05:00,2025-02-21 12:45:00--2025-02-21 13:20:00"
        };
        

        for (int i = 0; i < persons.length; i++) {
            String timeInOut = timeInOutSamples[i % timeInOutSamples.length];
            tinkles.add(new Tinkle(persons[i], timeInOut));
        }

        return tinkles.toArray(new Tinkle[0]);
    }
}
