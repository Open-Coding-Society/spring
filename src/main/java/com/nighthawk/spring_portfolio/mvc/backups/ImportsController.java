package com.nighthawk.spring_portfolio.mvc.backups;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.sqlite.SQLiteException;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
@Controller
@RequestMapping("/api/imports/")
public class ImportsController {

    private static final String DB_PATH = "./volumes/sqlite.db";
    private static final String BACKUP_DIR = "./volumes/backups/";

    private final ObjectMapper objectMapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final Object lock = new Object();

    

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void initializeOnStartup() {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
            // Enable WAL mode for better concurrency
            enableWalMode(connection);
            
            // Set a busy timeout to wait for locks
            setBusyTimeout(connection, 30000); // 30 seconds
            
            // Check SQLite version for debugging
            checkSqliteVersion(connection);
            
            // Verify database integrity
            verifyDatabaseIntegrity(connection);
            
            System.out.println("Database initialized successfully in WAL mode.");
        } catch (SQLException e) {
            e.printStackTrace();
            System.err.println("Failed to initialize database: " + e.getMessage());
        }
    }

    @PostMapping("/manual")
    public String manualImport(@RequestParam("file") MultipartFile file, Model model) {
        synchronized (lock) {
            if (file.isEmpty()) {
                model.addAttribute("message", "No file uploaded.");
                return "db_management/db_error";
            }

            try {
                String result = importFromMultipartFile(file);
                manageBackups();
                model.addAttribute("message", result);
                return "db_management/db_success";
            } catch (Exception e) {
                e.printStackTrace();
                model.addAttribute("message", "Failed to process the uploaded file: " + e.getMessage());
                return "db_management/db_error";
            }
        }
    }

    public String importFromMultipartFile(MultipartFile file) {
        try {
            InputStream inputStream = file.getInputStream();
            String rawJson = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    
            // Parse the JSON data first
            Map<String, List<Map<String, Object>>> data = objectMapper.readValue(rawJson, Map.class);
            
            // FIRST PASS: Create all tables that don't exist yet
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
                connection.setAutoCommit(false); // Start transaction
                
                // Get existing tables
                Set<String> existingTables = getAllTables(connection);
                
                // Create all tables first before attempting to insert any data
                for (Map.Entry<String, List<Map<String, Object>>> entry : data.entrySet()) {
                    String tableName = sanitizeTableName(entry.getKey());
                    List<Map<String, Object>> tableData = entry.getValue();
                    
                    if (!tableName.endsWith("_seq") && !tableData.isEmpty() && !existingTables.contains(tableName)) {
                        System.out.println("Pre-creating table: " + tableName);
                        Set<String> columns = tableData.get(0).keySet();
                        createTable(connection, tableName, columns);
                    }
                }
                
                connection.commit();
            }
            
            // SECOND PASS: Process all the data normally
            sanitizeAndProcessData(data, true);
            
            return "Data imported successfully from uploaded file: " + file.getOriginalFilename();
        } catch (Exception e) {
            e.printStackTrace();
            return "Failed to import data: " + e.getMessage();
        }
    }

    private String importFromFile(File jsonFile) {
        int retries = 3;
        while (retries > 0) {
            try {
                // First establish a connection and set WAL mode BEFORE starting any transaction
                try (Connection setupConnection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
                    // Enable WAL mode for better concurrency
                    enableWalMode(setupConnection);
                    setBusyTimeout(setupConnection, 30000); // 30 seconds
                    
                    // Check SQLite version for debugging
                    checkSqliteVersion(setupConnection);
                    
                    // Verify database integrity
                    verifyDatabaseIntegrity(setupConnection);
                }
                
                // Now create a new connection for the transaction
                try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
                    connection.setAutoCommit(false); // Start transaction
                    
                    // Read and parse JSON file
                    String rawJson = new String(Files.readAllBytes(jsonFile.toPath()));
                    
                    objectMapper.enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
                    objectMapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
                    
                    BackupData backupData = objectMapper.readValue(rawJson, BackupData.class);
                    Map<String, List<Map<String, Object>>> data = backupData.getTables();
                    
                    // Sanitize and process data with removeExcessData flag set to true
                    sanitizeAndProcessData(data, true);
                    
                    connection.commit(); // Commit transaction
                    return "Data imported successfully from JSON file: " + jsonFile.getAbsolutePath();
                }
            } catch (SQLiteException e) {
                if (e.getMessage().contains("database is locked")) {
                    retries--;
                    try {
                        Thread.sleep(100); // Wait before retrying
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return "Thread interrupted while waiting to retry.";
                    }
                } else {
                    e.printStackTrace();
                    return "Failed to import data: " + e.getMessage();
                }
            } catch (Exception e) {
                e.printStackTrace();
                return "Failed to import data: " + e.getMessage();
            }
        }
        return "Failed to acquire database lock after retries.";
    }

    public static class BackupData {
        private Map<String, List<Map<String, Object>>> tables = new HashMap<>();

        @JsonAnySetter
        public void set(String key, Object value) {
            if (value instanceof List) {
                tables.put(key, (List<Map<String, Object>>) value);
            } else {
                throw new IllegalArgumentException("Expected a List<Map<String, Object>> for key: " + key);
            }
        }

        public Map<String, List<Map<String, Object>>> getTables() {
            return tables;
        }

        public void setTables(Map<String, List<Map<String, Object>>> tables) {
            this.tables = tables;
        }
    }

    private void initializeDatabase() {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
            // Enable WAL mode for better concurrency
            enableWalMode(connection);

            // Set a busy timeout to wait for locks
            setBusyTimeout(connection, 30000); // 30 seconds

            // Check SQLite version for debugging
            checkSqliteVersion(connection);

            // Verify database integrity
            verifyDatabaseIntegrity(connection);

            System.out.println("Database initialized successfully.");
        } catch (SQLException e) {
            e.printStackTrace();
            System.err.println("Failed to initialize database: " + e.getMessage());
        }
    }

    private void enableWalMode(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL;");
        }
    }

    private void setBusyTimeout(Connection connection, int timeoutMillis) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + timeoutMillis + ";");
        }
    }

    private void checkSqliteVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT sqlite_version();")) {
            if (resultSet.next()) {
                System.out.println("SQLite Version: " + resultSet.getString(1));
            }
        }
    }

    private void verifyDatabaseIntegrity(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check;")) {
            while (resultSet.next()) {
                System.out.println("Integrity Check: " + resultSet.getString(1));
            }
        }
    }

    private void sanitizeAndProcessData(Map<String, List<Map<String, Object>>> data, boolean removeExcessData) throws SQLException {
        // First establish a connection and set WAL mode
        try (Connection setupConnection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
            enableWalMode(setupConnection);
            setBusyTimeout(setupConnection, 30000); // 30 seconds
        }
        
        // Now create a new connection for the data operations
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH)) {
            connection.setAutoCommit(false); // Start transaction
            
            // Get a list of all existing tables in the database
            Set<String> allTables = getAllTables(connection);
            Set<String> tablesInJson = new HashSet<>(data.keySet());
            
            // Update sequence tables first
            updateSequenceTables(connection, data);
            
            // Insert data into other tables
            for (Map.Entry<String, List<Map<String, Object>>> entry : data.entrySet()) {
                String tableName = sanitizeTableName(entry.getKey());
                List<Map<String, Object>> tableData = entry.getValue();
                
                if (!tableName.endsWith("_seq")) { // Skip sequence tables
                    // First make sure the table exists with all necessary columns
                    try {
                        ensureTableExists(connection, tableName, tableData, removeExcessData);
                        
                        if (removeExcessData) {
                            // Delete all existing data and replace with the JSON data
                            clearTableData(connection, tableName);
                        }
                        
                        // Only attempt to insert data if we have any
                        if (!tableData.isEmpty()) {
                            insertTableData(connection, tableName, tableData);
                        }
                    } catch (SQLException e) {
                        System.err.println("Error processing table " + tableName + ": " + e.getMessage());
                        throw e; // Rethrow to roll back the transaction
                    }
                }
            }
            
            // Handle tables that exist in DB but not in JSON
            if (removeExcessData) {
                for (String tableName : allTables) {
                    // Skip system tables and sequence tables for deletion
                    if (!tableName.startsWith("sqlite_") && !tablesInJson.contains(tableName) && !tableName.endsWith("_seq")) {
                        System.out.println("Dropping table not in import: " + tableName);
                        dropTable(connection, tableName);
                    }
                }
            }
            
            connection.commit(); // Commit transaction
        }
    }

    private Set<String> getAllTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();
        DatabaseMetaData metaData = connection.getMetaData();
        
        try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        
        return tables;
    }
    
    private void dropTable(Connection connection, String tableName) throws SQLException {
        String sql = "DROP TABLE IF EXISTS " + tableName;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            System.out.println("Dropped table: " + tableName);
        }
    }
    
    private void clearTableData(Connection connection, String tableName) throws SQLException {
        String sql = "DELETE FROM " + tableName;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            System.out.println("Cleared all data from table: " + tableName);
        }
    }

    private void updateSequenceTables(Connection connection, Map<String, List<Map<String, Object>>> data) throws SQLException {
        for (Map.Entry<String, List<Map<String, Object>>> entry : data.entrySet()) {
            String tableName = entry.getKey();
            if (tableName.endsWith("_seq")) { // Check if it's a sequence table
                // Create the sequence table if it doesn't exist
                createSequenceTableIfNotExists(connection, tableName);

                List<Map<String, Object>> tableData = entry.getValue();
                if (!tableData.isEmpty()) {
                    Object nextValObj = tableData.get(0).get("next_val");
                    long nextValFromJson = 0;

                    // Safely handle Integer or Long values
                    if (nextValObj instanceof Number) {
                        nextValFromJson = ((Number) nextValObj).longValue();
                    } else {
                        throw new IllegalArgumentException("next_val must be a number (Integer or Long)");
                    }

                    updateSequenceValue(connection, tableName, nextValFromJson);
                }
            }
        }
    }

    private void createSequenceTableIfNotExists(Connection connection, String tableName) throws SQLException {
        if (!tableExists(connection, tableName)) {
            String sql = "CREATE TABLE " + tableName + " (next_val BIGINT NOT NULL)";
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                // Initialize the sequence value to 0 or 1
                String initSql = "INSERT INTO " + tableName + " (next_val) VALUES (0)";
                statement.execute(initSql);
                System.out.println("Created table: " + tableName);
            }
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        try (ResultSet resultSet = meta.getTables(null, null, tableName, null)) {
            return resultSet.next();
        }
    }

    private void updateSequenceValue(Connection connection, String tableName, long nextValFromJson) throws SQLException {
        String sql = "UPDATE " + tableName + " SET next_val = CASE WHEN next_val > ? THEN next_val ELSE ? END";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, nextValFromJson);
            statement.setLong(2, nextValFromJson);
            statement.executeUpdate();
        }
    }

    private String sanitizeTableName(String tableName) {
        return tableName.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private void insertTableData(Connection connection, String tableName, List<Map<String, Object>> tableData) throws SQLException {
        if (tableData.isEmpty()) {
            System.out.println("No data to insert for table: " + tableName);
            return;
        }
    
        Set<String> columns = tableData.get(0).keySet();
        
        // Verify table exists before attempting insert
        if (!tableExists(connection, tableName)) {
            System.out.println("Table " + tableName + " doesn't exist. Creating it now...");
            createTable(connection, tableName, columns);
        }
        
        // Verify all columns exist
        Set<String> existingColumns = getExistingColumns(connection, tableName);
        for (String column : columns) {
            if (!existingColumns.contains(column)) {
                System.out.println("Adding missing column: " + column + " to table: " + tableName);
                addColumn(connection, tableName, column);
            }
        }
        
        // Now build the insert query based on the actual columns in the database
        String sql = buildInsertQuery(tableName, columns);
    
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            int batchSize = 0;
            for (Map<String, Object> row : tableData) {
                int index = 1;
                for (String column : columns) {
                    preparedStatement.setObject(index++, row.get(column));
                }
                preparedStatement.addBatch();
                batchSize++;
                
                // Execute in batches of 100 to avoid memory issues
                if (batchSize >= 100) {
                    preparedStatement.executeBatch();
                    batchSize = 0;
                }
            }
    
            // Execute any remaining items in the batch
            if (batchSize > 0) {
                preparedStatement.executeBatch();
            }
            
            System.out.println("Successfully inserted " + tableData.size() + " rows into " + tableName);
        } catch (SQLException e) {
            System.err.println("Error inserting data into " + tableName + ": " + e.getMessage());
            throw e;
        }
    }

    private String buildInsertQuery(String tableName, Set<String> columns) {
        String columnList = String.join(", ", columns);
        String valuePlaceholders = String.join(", ", Collections.nCopies(columns.size(), "?"));

        // Using INSERT instead of INSERT OR REPLACE since we're now deleting all data first
        return "INSERT INTO " + tableName + " (" + columnList + ") VALUES (" + valuePlaceholders + ")";
    }

    private Set<String> getExistingColumns(Connection connection, String tableName) throws SQLException {
        Set<String> columns = new HashSet<>();
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        
        return columns;
    }

    private void ensureTableExists(Connection connection, String tableName, List<Map<String, Object>> tableData, boolean removeExcessColumns) throws SQLException {
        // If the data is empty, create a basic table structure instead of skipping
        if (tableData.isEmpty()) {
            System.out.println("No data provided for table: " + tableName + ". Creating with basic structure.");
            if (!tableExists(connection, tableName)) {
                // Create a basic table if it doesn't exist
                String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                             "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                             "name TEXT, " +
                             "description TEXT, " +
                             "created_date TEXT" +
                             ")";
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute(sql);
                    System.out.println("Created basic table structure for: " + tableName);
                }
            }
            return;
        }
    
        Set<String> columnsInJson = tableData.get(0).keySet();
        
        // Check if the table exists
        boolean tableExists = tableExists(connection, tableName);
        
        if (!tableExists) {
            // Create a new table with exactly the columns in the JSON
            System.out.println("Creating new table: " + tableName + " with columns: " + columnsInJson);
            createTable(connection, tableName, columnsInJson);
        } else if (removeExcessColumns) {
            // Get existing columns in the database
            Set<String> existingColumns = getExistingColumns(connection, tableName);
            
            // Identify columns to remove and add
            Set<String> columnsToRemove = new HashSet<>(existingColumns);
            columnsToRemove.removeAll(columnsInJson);
            
            // If we have columns to remove, recreate the table
            if (!columnsToRemove.isEmpty()) {
                System.out.println("Recreating table: " + tableName + " to remove columns: " + columnsToRemove);
                // Need to recreate the table to remove columns
                recreateTableWithNewSchema(connection, tableName, columnsInJson);
            } else {
                // Just add any missing columns
                for (String column : columnsInJson) {
                    if (!existingColumns.contains(column)) {
                        System.out.println("Adding column: " + column + " to table: " + tableName);
                        addColumn(connection, tableName, column);
                    }
                }
            }
        } else {
            // Just ensure all columns from JSON exist in the table
            Set<String> existingColumns = getExistingColumns(connection, tableName);
            for (String column : columnsInJson) {
                if (!existingColumns.contains(column)) {
                    System.out.println("Adding column: " + column + " to table: " + tableName);
                    addColumn(connection, tableName, column);
                }
            }
        }
    }

    private void recreateTableWithNewSchema(Connection connection, String tableName, Set<String> newColumns) throws SQLException {
        // Create the new table with a temporary name
        String tempTableName = tableName + "_temp";
        
        // Drop temp table if it exists from a previous failed attempt
        dropTable(connection, tempTableName);
        
        // Create new table with the exact columns we want
        createTable(connection, tempTableName, newColumns);
        
        // Since we'll be clearing all the data anyway, we can just drop the original table
        dropTable(connection, tableName);
        
        // Rename the temp table to the original name
        String sql = "ALTER TABLE " + tempTableName + " RENAME TO " + tableName;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            System.out.println("Recreated table " + tableName + " with new schema");
        }
    }

    private void createTable(Connection connection, String tableName, Set<String> columns) throws SQLException {
        StringBuilder sqlBuilder = new StringBuilder("CREATE TABLE IF NOT EXISTS " + tableName + " (");
        
        boolean hasIdColumn = columns.stream().anyMatch(col -> col.equalsIgnoreCase("id"));
        
        for (String column : columns) {
            if (column.equalsIgnoreCase("id") && hasIdColumn) {
                sqlBuilder.append(column).append(" INTEGER PRIMARY KEY AUTOINCREMENT,");
            } else if (column.toLowerCase().endsWith("_id") || column.toLowerCase().equals("id")) {
                // Foreign keys or other ID fields are usually integers
                sqlBuilder.append(column).append(" INTEGER,");
            } else if (column.toLowerCase().contains("date") || column.toLowerCase().contains("time")) {
                // Date/time fields
                sqlBuilder.append(column).append(" TEXT,");
            } else if (column.toLowerCase().contains("is_") || column.toLowerCase().startsWith("has_") || 
                      column.toLowerCase().equals("active") || column.toLowerCase().equals("enabled")) {
                // Boolean fields
                sqlBuilder.append(column).append(" BOOLEAN,");
            } else if (column.toLowerCase().contains("count") || column.toLowerCase().contains("number") || 
                      column.toLowerCase().contains("amount") || column.toLowerCase().contains("quantity")) {
                // Numeric fields
                sqlBuilder.append(column).append(" INTEGER,");
            } else if (column.toLowerCase().contains("price") || column.toLowerCase().contains("cost") || 
                      column.toLowerCase().contains("rate")) {
                // Decimal fields
                sqlBuilder.append(column).append(" REAL,");
            } else {
                // Default to TEXT for everything else
                sqlBuilder.append(column).append(" TEXT,");
            }
        }
        
        // Remove the trailing comma
        if (columns.size() > 0) {
            sqlBuilder.deleteCharAt(sqlBuilder.length() - 1);
        }
        
        sqlBuilder.append(")");
        
        String sql = sqlBuilder.toString();
        System.out.println("Creating table with SQL: " + sql);
        
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            System.out.println("Successfully created table: " + tableName);
        } catch (SQLException e) {
            System.err.println("Error creating table " + tableName + ": " + e.getMessage());
            throw e;

 

        }
    }

    private void addColumn(Connection connection, String tableName, String columnName) throws SQLException {
        String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " TEXT";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void manageBackups() {
        File backupsDir = new File(BACKUP_DIR);
        if (!backupsDir.exists() || !backupsDir.isDirectory()) {
            return;
        }

        File[] backupFiles = backupsDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".json"));

        if (backupFiles == null || backupFiles.length <= 3) {
            return;
        }

        Arrays.sort(backupFiles, Comparator.comparingLong(File::lastModified));

        for (int i = 0; i < backupFiles.length - 3; i++) {
            if (backupFiles[i].delete()) {
                System.out.println("Deleted old backup: " + backupFiles[i].getName());
            } else {
                System.out.println("Failed to delete old backup: " + backupFiles[i].getName());
            }
        }
    }

    @GetMapping("/backups")
    public String getBackupsList(Model model) {
        try {
            List<BackupFileInfo> backups = getAllBackupFiles();
            model.addAttribute("backups", backups);
            return "db_management/backups";
        } catch (Exception e) {
            model.addAttribute("message", "Error fetching backups: " + e.getMessage());
            return "db_management/db_error";
        }
    }

    private static final String LOG_FILE_PATH = "./volumes/logs/restore_operations.log";

    @PostMapping("/revert")
    public String revertToBackup(@RequestParam("filename") String filename, Model model) {
        synchronized (lock) {
            try {
                File backupFile = new File(BACKUP_DIR + filename);
                if (!backupFile.exists()) {
                    model.addAttribute("message", "Backup file not found.");
                    return "db_management/db_error";
                }
                
                // Log the restore operation
                logRestoreOperation(filename);
                
                String result = importFromFile(backupFile);
                model.addAttribute("message", result);
                return "db_management/db_success";
            } catch (Exception e) {
                e.printStackTrace();
                // Log the error too
                logRestoreOperation(filename + " (FAILED: " + e.getMessage() + ")");
                model.addAttribute("message", "Failed to revert to backup: " + e.getMessage());
                return "db_management/db_error";
            }
        }
    }

    private void logRestoreOperation(String filename) {
        try {
            // Create logs directory if it doesn't exist
            File logDir = new File("./volumes/logs/");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            
            // Get current timestamp
            LocalDateTime now = LocalDateTime.now();
            String timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            // Append to log file
            Path logPath = Paths.get(LOG_FILE_PATH);
            String logEntry = timestamp + " - Restore operation performed: " + filename + "\n";
            
            Files.write(logPath, logEntry.getBytes(), 
                        java.nio.file.StandardOpenOption.CREATE, 
                        java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Failed to log restore operation: " + e.getMessage());
        }
    }

    @GetMapping("/logs")
    public String viewRestoreLogs(Model model) {
        try {
            List<String> logs = readLogFile();
            model.addAttribute("logs", logs);
            return "db_management/restore_logs";
        } catch (IOException e) {
            model.addAttribute("message", "Failed to read log file: " + e.getMessage());
            return "db_management/db_error";
        }
    }

    private List<String> readLogFile() throws IOException {
        List<String> logs = new ArrayList<>();
        File logFile = new File(LOG_FILE_PATH);
        
        if (!logFile.exists()) {
            return logs;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logs.add(line);
            }
        }
    
        Collections.reverse(logs);
        return logs;
    }

    @GetMapping("/view")
    public String viewBackupDetails(@RequestParam("filename") String filename, Model model) {
        try {
            File backupFile = new File(BACKUP_DIR + filename);
            if (!backupFile.exists()) {
                model.addAttribute("message", "Backup file not found.");
                return "db_management/db_error";
            }
            
            String rawJson = new String(Files.readAllBytes(backupFile.toPath()));
            BackupData backupData = objectMapper.readValue(rawJson, BackupData.class);
            
            model.addAttribute("filename", filename);
            model.addAttribute("tables", backupData.getTables());
            model.addAttribute("creationDate", new java.util.Date(backupFile.lastModified()));
            model.addAttribute("fileSize", backupFile.length() / 1024); // Size in KB
            
            return "db_management/backup-details";
        } catch (Exception e) {
            e.printStackTrace();
            model.addAttribute("message", "Failed to read backup: " + e.getMessage());
            return "db_management/db_error";
        }
    }

    private List<BackupFileInfo> getAllBackupFiles() {
        File backupsDir = new File(BACKUP_DIR);
        if (!backupsDir.exists() || !backupsDir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] backupFiles = backupsDir.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".json"));
        if (backupFiles == null || backupFiles.length == 0) {
            return Collections.emptyList();
        }

        return Arrays.stream(backupFiles)
            .map(file -> new BackupFileInfo(
                file.getName(),
                new java.util.Date(file.lastModified()),
                file.length() / 1024, // Size in KB
                getTableCountFromBackup(file)
            ))
            .sorted(Comparator.comparing(BackupFileInfo::getCreationDate).reversed())
            .collect(Collectors.toList());
    }

    private int getTableCountFromBackup(File file) {
        try {
            String rawJson = new String(Files.readAllBytes(file.toPath()));
            BackupData backupData = objectMapper.readValue(rawJson, BackupData.class);
            return backupData.getTables().size();
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    // Class to hold backup file information for display
    public static class BackupFileInfo {
        private final String filename;
        private final java.util.Date creationDate;
        private final long sizeKB;
        private final int tableCount;

        public BackupFileInfo(String filename, java.util.Date creationDate, long sizeKB, int tableCount) {
            this.filename = filename;
            this.creationDate = creationDate;
            this.sizeKB = sizeKB;
            this.tableCount = tableCount;
        }

        public String getFilename() {
            return filename;
        }

        public java.util.Date getCreationDate() {
            return creationDate;
        }

        public long getSizeKB() {
            return sizeKB;
        }

        public int getTableCount() {
            return tableCount;
        }
    }
}
