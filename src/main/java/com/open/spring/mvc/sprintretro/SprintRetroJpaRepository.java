package com.open.spring.mvc.sprintretro;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SprintRetroJpaRepository extends JpaRepository<SprintRetroEntity, Long> {

    /** Every sprint in the order of the year. */
    List<SprintRetroEntity> findAllByOrderByStartDateAscIdAsc();
}
