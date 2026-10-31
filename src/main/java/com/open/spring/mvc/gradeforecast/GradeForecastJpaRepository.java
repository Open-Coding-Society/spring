package com.open.spring.mvc.gradeforecast;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.person.Person;

public interface GradeForecastJpaRepository extends JpaRepository<GradeForecastEntity, Long> {

    /** A person's forecasts, oldest first, so the list reads as a timeline. */
    List<GradeForecastEntity> findByPersonOrderByCreatedAtAscIdAsc(Person person);

    Optional<GradeForecastEntity> findFirstByPersonOrderByCreatedAtDescIdDesc(Person person);

    Optional<GradeForecastEntity> findByIdAndPerson(Long id, Person person);
}
