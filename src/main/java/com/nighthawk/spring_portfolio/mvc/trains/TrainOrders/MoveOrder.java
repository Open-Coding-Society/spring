package com.nighthawk.spring_portfolio.mvc.trains.TrainOrders;

import com.nighthawk.spring_portfolio.mvc.trains.Train;
import com.nighthawk.spring_portfolio.mvc.trains.TrainJPARepository;
import com.nighthawk.spring_portfolio.mvc.trains.TrainOrder;
import com.nighthawk.spring_portfolio.mvc.trains.TrainOrderJPARepository;
import com.nighthawk.spring_portfolio.mvc.trains.TrainStation;
import com.nighthawk.spring_portfolio.mvc.trains.TrainStationJPARepository;

import java.time.Instant;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;

public class MoveOrder extends TrainOrder{

    @Autowired
    private TrainJPARepository trainJPARepository;

    @Autowired
    private TrainStationJPARepository trainStationJPARepository;

    @Autowired
    private TrainOrderJPARepository repository;

    @Override
    public boolean doSimulation() { //do a single/partial simulation step
        Map<String,String> orderInfo = this.getOrderInfo();
        Instant current = Instant.now();
        Date startTime = this.getLastTime();
        Train train = this.getTrain();

        if(!orderInfo.containsKey("end") || !orderInfo.containsKey("start")){
            this.setRepeat(false);
            return true; //end segment, don't update timer, prepare to remove order
        }
        
        Long stationId = Long.valueOf(orderInfo.get("end"));
        Long startStationId = Long.valueOf(orderInfo.get("start"));

        if(!trainStationJPARepository.existsById(stationId) || !trainStationJPARepository.existsById(startStationId)){
            this.setRepeat(false);
            return true; //end segment, don't update timer, prepare to remove order
        }

        TrainStation trainStation = trainStationJPARepository.getById(stationId);
        TrainStation startStation = trainStationJPARepository.getById(startStationId);

        float deltaPosition = Math.abs(trainStation.getPosition().floatValue() - this.getTrain().getPosition().floatValue());

        //200 seconds to go 20000 meters, extra time for terrain difficulty
        float calcTime = 200 * (deltaPosition + trainStation.getTerrain().floatValue()  * deltaPosition); 

        if(startTime.toInstant().plusSeconds((long)calcTime).isAfter(current)){
            this.setLastTime(Date.from(current)); //update time
            repository.save(this);
            float totalDeltaPosition = Math.abs(trainStation.getPosition().floatValue() - startStation.getPosition().floatValue()); //total distance between where train order started, and station it will end
            float totalTime = 200 * (totalDeltaPosition + trainStation.getTerrain().floatValue()  * totalDeltaPosition); //get the time the total trip will take
            float proportion = (totalTime-calcTime)/totalTime; //proportion of the trip remaining from 0-1
            float newPosition = (1 - proportion) * startStation.getPosition().floatValue() + trainStation.getPosition().floatValue() * proportion; //line segment function: s(p1,p2,t)=(1-t)*p1 + p2*t
            train.setPosition(newPosition); //set the trains position
            trainJPARepository.save(train); //save the train

            return false; //since we did not finish segment, return false
        } else {
            train.setPosition(trainStation.getPosition()); //set the train's position
            trainJPARepository.save(train); //save the train
            this.setLastTime(Date.from(startTime.toInstant().plusSeconds((long)calcTime))); //update time
            return true; //since we finished the segment, return true
        }
    }
}
