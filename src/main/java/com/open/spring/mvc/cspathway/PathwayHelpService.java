package com.open.spring.mvc.cspathway;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;

/**
 * Service for managing help requests sent by students via the game's "red flag" button.
 * 
 * <p>This service handles the creation of help requests, enforces a cooldown period 
 * between requests to prevent spam, and provides methods to retrieve recent requests 
 * for the teacher's dashboard.</p>
 */
@Service
public class PathwayHelpService {
    /** The minimum time required between consecutive help requests from the same student. */
    static final Duration COOLDOWN = Duration.ofMinutes(1);
    
    /** The maximum allowed length for a help message. */
    static final int MAX_MESSAGE_LENGTH = 500;
    
    /** The maximum allowed length for a station identifier. */
    static final int MAX_STATION_LENGTH = 120;

    private static final Logger logger = LoggerFactory.getLogger(PathwayHelpService.class);

    private final PathwayHelpJpaRepository repository;
    private final PersonJpaRepository personRepository;
    private final Clock clock;

    /**
     * Standard constructor using the system UTC clock.
     * 
     * @param repository The repository for persisting help requests.
     * @param personRepository The repository for looking up student names.
     */
    @Autowired
    public PathwayHelpService(PathwayHelpJpaRepository repository, PersonJpaRepository personRepository) {
        this(repository, personRepository, Clock.systemUTC());
    }

    /**
     * Constructor for testing, allowing for a mocked clock.
     * 
     * @param repository The repository for persisting help requests.
     * @param personRepository The repository for looking up student names.
     * @param clock The clock to use for time-based operations.
     */
    PathwayHelpService(PathwayHelpJpaRepository repository, PersonJpaRepository personRepository, Clock clock) {
        this.repository = repository;
        this.personRepository = personRepository;
        this.clock = clock;
    }

    /**
     * Records a new help request from a student.
     * 
     * <p>This method validates the request content, checks for the cooldown period, 
     * and persists the new request.</p>
     *
     * @param uid   The unique identifier of the student.
     * @param body  The {@link PathwayHelpDtoRecordRequest} containing the request details.
     * @return The saved {@link PathwayHelpEntity}.
     * @throws IllegalArgumentException if the uid, level, or message is invalid.
     * @throws PathwayHelpExceptionTooSoon if the student is still within the cooldown period.
     */
    public PathwayHelpEntity send(String uid, PathwayHelpDtoRecordRequest body) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("Student uid is required.");
        }
        if (body == null) {
            throw new IllegalArgumentException("The help request is empty.");
        }
        CsPathwayLevel level = CsPathwayLevel.fromKey(body.levelKey())
                .orElseThrow(() -> new IllegalArgumentException("Unknown level: " + body.levelKey()));
        String message = requireText(body.message(), "message", MAX_MESSAGE_LENGTH);
        String station = body.station() == null || body.station().isBlank()
                ? null
                : requireText(body.station(), "station", MAX_STATION_LENGTH);

        long now = clock.millis();
        repository.findFirstByUidOrderByCreatedAtDesc(uid).ifPresent(previous -> {
            long waitMillis = previous.getCreatedAt() + COOLDOWN.toMillis() - now;
            if (waitMillis > 0) {
                throw new PathwayHelpExceptionTooSoon(Math.max(1, Duration.ofMillis(waitMillis).toSeconds()));
            }
        });

        PathwayHelpEntity request = new PathwayHelpEntity();
        request.setId(UUID.randomUUID().toString());
        request.setUid(uid);
        request.setLevelKey(level.getKey());
        request.setStation(station);
        request.setMessage(message);
        request.setCreatedAt(now);
        PathwayHelpEntity saved = repository.save(request);
        logger.info("CS Pathway help request from {} at {} / {}", uid, level.getKey(), station);
        return saved;
    }

    /**
     * Retrieves the most recent help requests, ordered by creation time, for the teacher's view.
     * 
     * @return A list of {@link PathwayHelpDtoRecordViewModel} objects.
     */
    public List<PathwayHelpDtoRecordViewModel> getRecentRequests() {
        Map<String, String> namesByUid = new HashMap<>();
        return repository.findTop200ByOrderByCreatedAtDesc().stream()
                .map(request -> new PathwayHelpDtoRecordViewModel(
                        namesByUid.computeIfAbsent(request.getUid(), this::displayName),
                        request.getUid(),
                        CsPathwayLevel.fromKey(request.getLevelKey())
                                .map(CsPathwayLevel::getDisplayName)
                                .orElse(request.getLevelKey()),
                        request.getStation(),
                        request.getMessage(),
                        request.getCreatedAtDate()))
                .toList();
    }

    /** Resolves a student's display name from their UID. */
    private String displayName(String uid) {
        Person person = personRepository.findByUid(uid);
        if (person == null || person.getName() == null || person.getName().isBlank()) {
            return uid;
        }
        return person.getName();
    }

    /** Validates that a text field is not empty and does not exceed the maximum length. */
    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The " + field + " is empty.");
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException("The " + field + " is longer than " + maxLength + " characters.");
        }
        return trimmed;
    }
}
