package com.open.spring.mvc.capstone;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.person.Person;

import java.util.Optional;

public interface CapstoneLikeJpaRepository extends JpaRepository<CapstoneLike, Long> {

    boolean existsByProjectIdAndPerson(String projectId, Person person);

    Optional<CapstoneLike> findByProjectIdAndPerson(String projectId, Person person);

    long countByProjectId(String projectId);

    void deleteByProjectIdAndPerson(String projectId, Person person);
}
