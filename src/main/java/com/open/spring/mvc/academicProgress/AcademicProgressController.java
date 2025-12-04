package com.open.spring.mvc.academicProgress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.data.vector.BaseVector;
import smile.data.vector.IntVector;
import smile.data.vector.StringVector;
import smile.io.Read;
import smile.regression.RandomForest;

@RestController
@RequestMapping("/api/academic-progress")
public class AcademicProgressController {

    @Autowired
    private AcademicProgressRepository repository;

    @Autowired
    private AcademicPredictionRepository predictionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RandomForest model;
    private Map<String, Map<String, Integer>> encoders;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PredictionRequest {
        private String assignmentType;
        private String difficultyLevel;
        private String topic;
    }

    @GetMapping("/train")
    public ResponseEntity<?> train() {
        try {
            String sql = "CREATE TABLE IF NOT EXISTS academic_progress (" +
                         "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                         "student_id INTEGER, " +
                         "assignment_id TEXT, " +
                         "score INTEGER, " +
                         "max_score INTEGER, " +
                         "submission_date TEXT, " +
                         "due_date TEXT, " +
                         "completion_status TEXT, " +
                         "assignment_type TEXT, " +
                         "difficulty_level TEXT, " +
                         "topic TEXT)";
            jdbcTemplate.execute(sql);

            String csvPath = "src/main/java/com/open/spring/mvc/academicProgress/fake-records.csv";
            
            DataFrame data = Read.csv(csvPath, CSVFormat.DEFAULT.withFirstRecordAsHeader());

            if (repository.count() == 0) {
                List<AcademicProgress> records = new ArrayList<>();
                for (int i = 0; i < data.nrows(); i++) {
                    Tuple row = data.get(i);
                    AcademicProgress ap = new AcademicProgress();
                    ap.setStudentId(((Number) row.getAs("student_id")).longValue());
                    ap.setAssignmentId(row.getString("assignment_id"));
                    ap.setScore(((Number) row.getAs("score")).intValue());
                    ap.setMaxScore(((Number) row.getAs("max_score")).intValue());
                    ap.setSubmissionDate(row.getString("submission_date"));
                    ap.setDueDate(row.getString("due_date"));
                    ap.setCompletionStatus(row.getString("completion_status"));
                    ap.setAssignmentType(row.getString("assignment_type"));
                    ap.setDifficultyLevel(row.getString("difficulty_level"));
                    ap.setTopic(row.getString("topic"));
                    records.add(ap);
                }
                repository.saveAll(records);
            }

            data = data.select("score", "assignment_type", "difficulty_level", "topic", "completion_status");

            this.encoders = new HashMap<>();

            String[] stringCols = {"assignment_type", "difficulty_level", "topic", "completion_status"};
            
            for (String col : stringCols) {
                StringVector vec = data.stringVector(col);
                int[] encoded = new int[vec.size()];
                Map<String, Integer> map = new HashMap<>();
                int id = 0;
                
                for (int i = 0; i < vec.size(); i++) {
                    String val = vec.get(i);
                    if (!map.containsKey(val)) {
                        map.put(val, id++);
                    }
                    encoded[i] = map.get(val);
                }
                
                encoders.put(col, map);

                data = data.drop(col).merge(IntVector.of(col, encoded));
            }

            Formula formula = Formula.lhs("score");
            
            this.model = RandomForest.fit(formula, data);

            return ResponseEntity.ok(Map.of(
                "message", "Model trained successfully",
                "rows_processed", data.nrows(),
                "columns", data.names(),
                "model_type", "RandomForest Regression"
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Training failed: " + e.getMessage()));
        }
    }

    @PostMapping("/predict")
    public ResponseEntity<?> predict(@RequestBody Map<String, Object> features) {
        if (model == null || encoders == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "Model not trained. Call /train first."));
        }

        try {
            String sql = "CREATE TABLE IF NOT EXISTS academic_prediction (" +
                         "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                         "assignment_type TEXT, " +
                         "difficulty_level TEXT, " +
                         "topic TEXT, " +
                         "completion_status TEXT, " +
                         "predicted_score REAL, " +
                         "created_at INTEGER)";
            jdbcTemplate.execute(sql);

            String[] featureNames = {"assignment_type", "difficulty_level", "topic", "completion_status"};
            
            BaseVector[] vectors = new BaseVector[featureNames.length];

            for (int i = 0; i < featureNames.length; i++) {
                String col = featureNames[i];
                String val = (String) features.get(col);

                if (val == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Missing feature: " + col));
                }

                if (!encoders.containsKey(col) || !encoders.get(col).containsKey(val)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Unknown value for feature '" + col + "': " + val));
                }

                int encodedVal = encoders.get(col).get(val);
                vectors[i] = IntVector.of(col, new int[]{ encodedVal });
            }

            DataFrame singleRow = DataFrame.of(vectors);
            Tuple instance = singleRow.stream().findFirst().orElseThrow();

            double prediction = model.predict(instance);

            AcademicPrediction record = new AcademicPrediction(
                (String) features.get("assignment_type"),
                (String) features.get("difficulty_level"),
                (String) features.get("topic"),
                (String) features.get("completion_status"),
                prediction
            );
            predictionRepository.save(record);

            return ResponseEntity.ok(Map.of(
                "predicted_score", prediction,
                "status", "success",
                "prediction_id", record.getId()
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Prediction failed: " + e.getMessage()));
        }
    }

    @GetMapping("/records")
    public ResponseEntity<List<AcademicProgress>> getRecords() {
        return ResponseEntity.ok(repository.findAll());
    }
}
