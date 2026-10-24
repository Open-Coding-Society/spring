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
 * Saves the help requests students send with the game's red flag button,
 * and lists them for the teacher page.
 */
@Service
public class PathwayHelpService {
    static final Duration COOLDOWN = Duration.ofMinutes(1);
    static final int MAX_MESSAGE_LENGTH = 500;
    static final int MAX_STATION_LENGTH = 120;

    private static final Logger logger = LoggerFactory.getLogger(PathwayHelpService.class);

    private final PathwayHelpJpaRepository repository;
    private final PersonJpaRepository personRepository;
    private final Clock clock;

    @Autowired
    public PathwayHelpService(PathwayHelpJpaRepository repository, PersonJpaRepository personRepository) {
        this(repository, personRepository, Clock.systemUTC());
    }

    PathwayHelpService(PathwayHelpJpaRepository repository, PersonJpaRepository personRepository, Clock clock) {
        this.repository = repository;
        this.personRepository = personRepository;
        this.clock = clock;
    }

    /**
     * Records a help request from the signed-in student.
     *
     * @throws IllegalArgumentException when the level is unknown or the text is empty or too long
     * @throws PathwayHelpExceptionTooSoon when the student sent one less than a minute ago
     */
    public PathwayHelpEntity send(String uid, PathwayHelpDtoRecord body) {
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

    /** Most recent requests first, for the teacher page. */
    public List<PathwayHelpDtoRecordView> getRecentRequests() {
        Map<String, String> namesByUid = new HashMap<>();
        return repository.findTop200ByOrderByCreatedAtDesc().stream()
                .map(request -> new PathwayHelpDtoRecordView(
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

    private String displayName(String uid) {
        Person person = personRepository.findByUid(uid);
        if (person == null || person.getName() == null || person.getName().isBlank()) {
            return uid;
        }
        return person.getName();
    }

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
