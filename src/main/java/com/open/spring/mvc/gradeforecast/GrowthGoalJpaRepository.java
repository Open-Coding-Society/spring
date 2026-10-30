package com.open.spring.mvc.gradeforecast;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.person.Person;

public interface GrowthGoalJpaRepository extends JpaRepository<GrowthGoalEntity, Long> {

    /** A person's goals, newest first. */
    List<GrowthGoalEntity> findByPersonOrderByCreatedAtDescIdDesc(Person person);

    Optional<GrowthGoalEntity> findFirstByPersonAndCheckedInAtIsNull(Person person);

    Optional<GrowthGoalEntity> findByIdAndPerson(Long id, Person person);
}
