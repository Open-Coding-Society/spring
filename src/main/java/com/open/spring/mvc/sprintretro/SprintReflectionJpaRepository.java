package com.open.spring.mvc.sprintretro;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.person.Person;

public interface SprintReflectionJpaRepository extends JpaRepository<SprintReflectionEntity, Long> {

    Optional<SprintReflectionEntity> findBySprintAndPerson(SprintRetroEntity sprint, Person person);

    List<SprintReflectionEntity> findBySprint(SprintRetroEntity sprint);

    List<SprintReflectionEntity> findByPerson(Person person);

    boolean existsBySprint(SprintRetroEntity sprint);
}
