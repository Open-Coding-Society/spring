package com.open.spring.mvc.challengeSubmission;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * JPA Repository for ChallengeSubmission entity.
 * Provides database operations for code runner submissions.
 */
public interface ChallengeSubmissionRepository extends JpaRepository<ChallengeSubmission, Long> {

    /**
     * Find a submission by user and lesson
     * 
     * @param userId    The user's UID
     * @param lessonKey The lesson identifier
     * @return Optional containing the submission if found
     */
    Optional<ChallengeSubmission> findByUserIdAndLessonKey(String userId, String lessonKey);

    /**
     * Find all submissions for a specific user
     * 
     * @param userId The user's UID
     * @return List of all submissions by this user
     */
    List<ChallengeSubmission> findByUserId(String userId);

    /**
     * Find all submissions for a specific lesson
     * 
     * @param lessonKey The lesson identifier
     * @return List of all submissions for this lesson
     */
    List<ChallengeSubmission> findByLessonKey(String lessonKey);

    /**
     * Check if a submission exists for a user and lesson
     * 
     * @param userId    The user's UID
     * @param lessonKey The lesson identifier
     * @return true if submission exists
     */
    boolean existsByUserIdAndLessonKey(String userId, String lessonKey);
}
