package com.nighthawk.spring_portfolio.mvc.bathroom.bathroomML;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.nighthawk.spring_portfolio.mvc.bathroom.Tinkle;

import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

@Component
public class BathroomPreprocess implements CommandLineRunner {

    @Autowired
    private BathroomService bathroomService;

    @Override
    public void run(String... args) throws Exception {
        List<Tinkle> logs = bathroomService.getAllLogs();

        // Create columns
        StringColumn nameCol = StringColumn.create("Name");
        DoubleColumn durationCol = DoubleColumn.create("Duration"); // in minutes
        StringColumn dateCol = StringColumn.create("Date");

        for (Tinkle log : logs) {
            for (LocalDateTime[] pair : log.getTimeInOutPairs()) {
                LocalDateTime timeIn = pair[0];
                LocalDateTime timeOut = pair[1];

                long minutes = Duration.between(timeIn, timeOut).toMinutes();

                nameCol.append(log.getPersonName());
                durationCol.append((double) minutes);
                dateCol.append(timeIn.toLocalDate().toString());
            }
        }

        Table table = Table.create("Bathroom Logs", nameCol, durationCol, dateCol);

        // Normalize duration (optional)
        normalizeColumn(table, "Duration");

        // Save to CSV
        File outputFile = new File("src/main/resources/data/bathroom_cleaned.csv");
        table.write().csv(outputFile);

        System.out.println("Preprocessing done. File saved as bathroom_cleaned.csv!");
    }

    private void normalizeColumn(Table table, String columnName) {
        DoubleColumn col = table.doubleColumn(columnName);
        double min = col.min();
        double max = col.max();
        for (int i = 0; i < col.size(); i++) {
            double norm = (col.getDouble(i) - min) / (max - min);
            col.set(i, norm);
        }
    }
}

