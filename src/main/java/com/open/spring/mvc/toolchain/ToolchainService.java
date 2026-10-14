package com.open.spring.mvc.toolchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.toolchain.ToolchainController.PairResponse;
import com.open.spring.mvc.toolchain.ToolchainController.ResultsRequest;
import com.open.spring.mvc.toolchain.ToolchainController.SessionView;
import com.open.spring.mvc.toolchain.ToolchainController.StartResponse;
import com.open.spring.mvc.toolchain.ToolchainStations.CheckResult;
import com.open.spring.mvc.toolchain.ToolchainStations.Evidence;

/**
 * One-time device pairing for Toolchain Trail.
 *
 * 1. The signed-in game starts a station and gets a single-use pairing code (5 minutes).
 * 2. The local agent exchanges that code once for an agent token (1 hour) scoped to that one session.
 * 3. The agent sends check results with "Authorization: Bearer <agent-token>"; the server decides pass/fail.
 * 4. The game polls the session until it is verified or failed.
 */
@Service
public class ToolchainService {
    static final Duration PAIRING_CODE_LIFETIME = Duration.ofMinutes(5);
    static final Duration AGENT_TOKEN_LIFETIME = Duration.ofHours(1);
    static final int MAX_CHECKS = 20;
    static final int MAX_OUTPUT_LENGTH = 4000;
    private static final List<String> KNOWN_OS = List.of("macos", "windows", "linux", "wsl");
    private static final String BAD_PAIRING_CODE = "Pairing code is invalid, expired, or already used";
    private static final String BAD_AGENT_TOKEN = "Agent token is invalid or expired. Start the station again in the game.";

    private static final Logger logger = LoggerFactory.getLogger(ToolchainService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolchainSessionRepository repository;
    private final ToolchainStations stationRules;
    private final Clock clock;

    @Autowired
    public ToolchainService(ToolchainSessionRepository repository, ToolchainStations stationRules) {
        this(repository, stationRules, Clock.systemUTC());
    }

    ToolchainService(ToolchainSessionRepository repository, ToolchainStations stationRules, Clock clock) {
        this.repository = repository;
        this.stationRules = stationRules;
        this.clock = clock;
    }

    public StartResponse start(String studentId, String stationId) {
        if (studentId == null || studentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to start a station.");
        }
        stationRules.requireAccessible(studentId, stationId);

        String code = randomToken();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(PAIRING_CODE_LIFETIME);

        ToolchainSession session = new ToolchainSession();
        session.setId(UUID.randomUUID().toString());
        session.setStudentUid(studentId);
        session.setStationId(stationId);
        session.setPairingCodeHash(sha256(code));
        session.setPairingExpiresAt(expiresAt.toEpochMilli());
        session.setStatus(ToolchainSession.WAITING_FOR_AGENT);
        session.setCreatedAt(now.toEpochMilli());
        session.setUpdatedAt(now.toEpochMilli());
        repository.save(session);

        return new StartResponse(UUID.fromString(session.getId()), stationId, code, expiresAt);
    }

    public PairResponse pair(String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_PAIRING_CODE);
        }
        String codeHash = sha256(code.strip());
        Instant now = clock.instant();
        // Atomically consume only an unused, unexpired code.
        if (repository.consumePairingCode(codeHash, now.toEpochMilli()) != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_PAIRING_CODE);
        }
        ToolchainSession session = repository.findByPairingCodeHash(codeHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_PAIRING_CODE));

        String agentToken = randomToken();
        Instant expiresAt = now.plus(AGENT_TOKEN_LIFETIME);
        session.setAgentTokenHash(sha256(agentToken));
        session.setAgentTokenExpiresAt(expiresAt.toEpochMilli());
        session.setStatus(ToolchainSession.PAIRED);
        session.setUpdatedAt(now.toEpochMilli());
        repository.save(session);

        return new PairResponse(UUID.fromString(session.getId()), session.getStationId(), agentToken, expiresAt);
    }

    /** Checks the agent's evidence for its own session and stores the outcome. */
    public SessionView submitResults(String sessionId, String authorization, ResultsRequest request) {
        ToolchainSession session = requireAgentSession(sessionId, authorization);
        if (request == null || request.checks() == null || request.checks().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No check results were sent.");
        }
        if (request.checks().size() > MAX_CHECKS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many check results.");
        }
        // A verified station stays verified; running the agent again cannot undo it.
        if (!ToolchainSession.VERIFIED.equals(session.getStatus())) {
            List<Evidence> evidence = request.checks().stream()
                    .map(item -> item == null ? null : new Evidence(item.id(), item.exitCode(), truncate(item.output())))
                    .toList();
            List<CheckResult> results = stationRules.evaluate(session.getStationId(), evidence);
            boolean verified = !results.isEmpty() && results.stream().allMatch(CheckResult::passed);
            session.setStatus(verified ? ToolchainSession.VERIFIED : ToolchainSession.FAILED);
            session.setAgentOs(KNOWN_OS.contains(request.os()) ? request.os() : null);
            session.setResults(writeResults(results));
            session.setUpdatedAt(clock.millis());
            repository.save(session);
            logger.info("Toolchain Trail {} for {}: {}", session.getStationId(), session.getStudentUid(), session.getStatus());
        }
        return view(session);
    }

    /** The game's poll: only the student who started the session can read it. */
    public SessionView getSession(String studentId, String sessionId) {
        ToolchainSession session = findSession(sessionId)
                .filter(found -> found.getStudentUid().equals(studentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found."));
        return view(session);
    }

    private ToolchainSession requireAgentSession(String sessionId, String authorization) {
        String token = bearerToken(authorization);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_AGENT_TOKEN);
        }
        ToolchainSession session = findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_AGENT_TOKEN));
        // The token is scoped: it only matches the hash stored on the session it was issued for.
        boolean matches = session.getAgentTokenHash() != null
                && MessageDigest.isEqual(
                        session.getAgentTokenHash().getBytes(StandardCharsets.US_ASCII),
                        sha256(token).getBytes(StandardCharsets.US_ASCII));
        boolean unexpired = session.getAgentTokenExpiresAt() != null && session.getAgentTokenExpiresAt() > clock.millis();
        if (!matches || !unexpired) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_AGENT_TOKEN);
        }
        return session;
    }

    private Optional<ToolchainSession> findSession(String sessionId) {
        try {
            return repository.findById(UUID.fromString(sessionId).toString());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private SessionView view(ToolchainSession session) {
        String status = session.getStatus();
        if (ToolchainSession.WAITING_FOR_AGENT.equals(status) && session.getPairingExpiresAt() <= clock.millis()) {
            status = ToolchainSession.EXPIRED;
        }
        return new SessionView(
                UUID.fromString(session.getId()),
                session.getStationId(),
                status,
                readResults(session.getResults()),
                Instant.ofEpochMilli(session.getPairingExpiresAt()),
                session.getUpdatedAt() == null ? null : Instant.ofEpochMilli(session.getUpdatedAt()));
    }

    static String bearerToken(String authorization) {
        if (authorization == null) return null;
        String value = authorization.strip();
        if (value.length() < 8 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = value.substring(7).strip();
        return token.isEmpty() ? null : token;
    }

    private static String truncate(String output) {
        if (output == null) return "";
        return output.length() > MAX_OUTPUT_LENGTH ? output.substring(0, MAX_OUTPUT_LENGTH) : output;
    }

    private static String writeResults(List<CheckResult> results) {
        try {
            return JSON.writeValueAsString(results);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store check results", e);
        }
    }

    private static List<CheckResult> readResults(String results) {
        if (results == null || results.isBlank()) return List.of();
        try {
            return JSON.readValue(results, new TypeReference<List<CheckResult>>() {});
        } catch (JsonProcessingException e) {
            logger.warn("Unreadable Toolchain Trail results: {}", e.getMessage());
            return List.of();
        }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
