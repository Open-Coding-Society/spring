package com.open.spring.mvc.toolchain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One Toolchain Trail station attempt: the student and station it belongs to,
 * the one-time pairing code the game showed, and the agent token the local
 * Python agent received for it. Codes and tokens are stored only as SHA-256
 * hashes, so a copy of the database cannot be used to report progress.
 * Times are epoch milliseconds so the same column type works on SQLite and MySQL.
 * The table is created by ToolchainSessionMigration (ddl-auto is none).
 */
@Entity
@Table(name = "toolchain_session")
@Data
@NoArgsConstructor
public class ToolchainSession {
    public static final String WAITING_FOR_AGENT = "WAITING_FOR_AGENT";
    public static final String PAIRED = "PAIRED";
    public static final String VERIFIED = "VERIFIED";
    public static final String FAILED = "FAILED";
    /** Never stored: reported for a session whose code expired before an agent paired. */
    public static final String EXPIRED = "EXPIRED";

    @Id
    private String id; // UUID

    private String studentUid;
    private String stationId;

    private String pairingCodeHash;
    private Long pairingExpiresAt;
    private Long pairingUsedAt; // set once, when an agent exchanges the code

    private String agentTokenHash;
    private Long agentTokenExpiresAt;

    private String status;
    private String agentOs; // what the agent says it runs on: macos, windows, linux, wsl
    private String results; // JSON array of ToolchainStations.CheckResult
    private Long createdAt;
    private Long updatedAt;
}
