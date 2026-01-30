package com.open.spring.mvc.generic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserContentRepository extends JpaRepository<UserContent, Long> {
    List<UserContent> findByType(ContentType type);

    List<UserContent> findByAuthorIdAndType(Long authorId, ContentType type);
}
