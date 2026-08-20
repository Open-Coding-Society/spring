package com.open.spring.mvc.person;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// Raised by the frontend when a user hits the reset rate limit and asks for admin help
// instead. An admin resolves it from the person/read portal, which grants the uid a batch
// of extra reset attempts via ResetCode.grantBonusAttempts.
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class ResetTicket {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @NotNull
    private String uid;

    // Snapshot of the person's name at request time, so the ticket stays readable even if
    // the account is later renamed or removed.
    private String name;

    private boolean resolved = false;

    private String createdAt;

    private String resolvedAt;

    private int attemptsGranted = 0;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ResetTicket(String uid, String name) {
        this.uid = uid;
        this.name = name;
        this.createdAt = LocalDateTime.now().format(FORMATTER);
    }

    public void markResolved(int attemptsGranted) {
        this.resolved = true;
        this.attemptsGranted = attemptsGranted;
        this.resolvedAt = LocalDateTime.now().format(FORMATTER);
    }
}
