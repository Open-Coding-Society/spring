package com.open.spring.mvc.generic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlgorithmicEventRepository extends JpaRepository<AlgorithmicEvent, Long> {
    List<AlgorithmicEvent> findByAlgoName(String algoName);

    List<AlgorithmicEvent> findByUserIdAndAlgoName(Long userId, String algoName);
}
