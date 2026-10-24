package com.open.spring.mvc.cspathway;

import java.util.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One "I need help" flag a student raised from a CS Pathway level.
 * Rows are kept (not replaced) so the teacher page shows every request.
 * Times are epoch milliseconds so the same column type works on SQLite and MySQL.
 * The table is created by CsPathwayHelpRequestMigration (ddl-auto is none).
 */
@Entity
@Table(name = "cs_pathway_help_request")
@Data
@NoArgsConstructor
public class CsPathwayHelpRequest {

    @Id
    private String id; // random UUID: auto-increment syntax differs between SQLite and MySQL

    private String uid;
    private String levelKey;
    private String station; // station or area the student was on; null when the level has none
    private String message;
    private Long createdAt;

    /** For the teacher page's date formatting. */
    public Date getCreatedAtDate() {
        return createdAt == null ? null : new Date(createdAt);
    }
}
