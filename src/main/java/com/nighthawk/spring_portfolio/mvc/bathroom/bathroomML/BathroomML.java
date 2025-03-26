package com.nighthawk.spring_portfolio.mvc.bathroom.bathroomML;

import tech.tablesaw.api.*;
import tech.tablesaw.columns.Column;
import weka.classifiers.Classifier;
import weka.classifiers.functions.Logistic;
import weka.classifiers.trees.J48;
import weka.core.*;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class BathroomML {
    public static void main(String[] args) throws Exception {
        // Step 1: Load and Clean Data using Tablesaw
        InputStream inputStream = BathroomML.class.getResourceAsStream("src/main/resources/data/bathroom_cleaned.csv");
        if (inputStream == null) {
            throw new IllegalArgumentException("File not found: bathroom_cleaned.csv");
        }
        Table bathroom_info = Table.read().csv(inputStream);

    }
}
