package com.nighthawk.spring_portfolio.mvc.bathroom.bathroomML;

import java.io.File;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.nighthawk.spring_portfolio.mvc.bathroom.Tinkle;

import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import java.time.Duration;


@Component
public class BathroomPreprocess implements CommandLineRunner {

    @Autowired
    private BathroomService bathroomService;

    @Override
    public void run(String... args) throws Exception {
        List<Tinkle> logs = bathroomService.getAllLogs();

        // Create columns
        StringColumn emailCol = StringColumn.create("Email");
        DoubleColumn durationCol = DoubleColumn.create("Duration"); // in minutes
        StringColumn dateCol = StringColumn.create("Date");

        for (Tinkle log : logs) {
            if (log.getTimeIn() != null && log.getTimeOut() != null) {
                long minutes = Duration.between(log.getTimeIn(), log.getTimeOut()).toMinutes();

                emailCol.append(log.getEmail());
                durationCol.append((double) minutes);
                dateCol.append(log.getDate().toString());
            }
        }

        Table table = Table.create("Bathroom Logs", emailCol, durationCol, dateCol);

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

