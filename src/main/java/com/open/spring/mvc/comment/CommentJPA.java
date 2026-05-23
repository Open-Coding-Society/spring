package com.open.spring.mvc.comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List; 

public interface CommentJPA extends JpaRepository<Comment, Long> {
    List<Comment> findByAuthor(String author); // Method to find announcement by author

    List<Comment> findByAssignment(String assignment);

    List<Comment> findByAssignmentOrderByTimestampDesc(String assignment);

    List<Comment> findAllByOrderByTimestampDesc();

    long countByAssignment(String assignment);

    boolean existsByAssignmentAndAuthor(String assignment, String author);

    void deleteByAssignmentAndAuthor(String assignment, String author);

    List<Comment> findByAssignmentAndText(String assignment, String text);

    // Thread/nested comment queries
    List<Comment> findByParentCommentId(Long parentCommentId);
    
    List<Comment> findByParentCommentIdOrderByTimestampDesc(Long parentCommentId);

    // Search queries
    @Query("SELECT c FROM Comment c WHERE c.assignment = :assignment AND c.parentCommentId IS NULL ORDER BY c.timestamp DESC")
    List<Comment> findTopLevelCommentsByAssignment(@Param("assignment") String assignment);

    @Query("SELECT c FROM Comment c WHERE c.assignment = :assignment AND (LOWER(c.text) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(c.author) LIKE LOWER(CONCAT('%', :query, '%'))) ORDER BY c.timestamp DESC")
    List<Comment> searchCommentsByAssignment(@Param("assignment") String assignment, @Param("query") String query);

    @Query("SELECT c FROM Comment c WHERE LOWER(c.text) LIKE LOWER(CONCAT('%', :query, '%')) OR LOWER(c.author) LIKE LOWER(CONCAT('%', :query, '%')) ORDER BY c.timestamp DESC")
    List<Comment> searchAllComments(@Param("query") String query);
}
