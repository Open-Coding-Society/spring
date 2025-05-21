package com.nighthawk.spring_portfolio.mvc.trains;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TrainOrderJPARepository extends JpaRepository<TrainOrder,Long> {
    TrainOrder getById(Long id);
    List<TrainOrder> getAllByTrain(Train train);
}
