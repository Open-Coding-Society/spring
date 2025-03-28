package com.nighthawk.spring_portfolio.mvc.bathroom.bathroomML;

import tech.tablesaw.api.Table;
import tech.tablesaw.api.BooleanColumn;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.NumericColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.plotly.Plot;
import tech.tablesaw.plotly.api.Histogram;
import tech.tablesaw.plotly.api.VerticalBarPlot;

import java.io.InputStream;

import com.nighthawk.spring_portfolio.hacks.tablesaw.TitanicAnalysis;

public class BathroomAnalysis {
    public static void main(String[] args) throws Exception {
        InputStream inputStream = BathroomAnalysis.class.getResourceAsStream("src/main/resources/data/bathroom_cleaned.csv");

        if (inputStream == null) {
            throw new IllegalArgumentException("File not found: bathroom_cleaned.csv");
        }
        Table bathroom_info = Table.read().csv(inputStream);

        StringColumn nameCol = StringColumn.create("Name");
        DoubleColumn durationCol = DoubleColumn.create("Duration"); // in minutes
        DoubleColumn durationByPeriodCol = DoubleColumn.create("Average Duration By Period"); // in minutes
        StringColumn dateCol = StringColumn.create("Date");
        BooleanColumn abnormalCol = BooleanColumn.create("Abnormal");

        

    }
}
