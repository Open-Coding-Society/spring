package com.open.spring.mvc.gradecalculator;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.person.Person;

public interface GradeCalculatorJpaRepository extends JpaRepository<GradeCalculatorEntity, Long> {

    Optional<GradeCalculatorEntity> findByPerson(Person person);
}
