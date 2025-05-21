package com.nighthawk.spring_portfolio.mvc.dice;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;

import lombok.Getter;

@RestController
@RequestMapping("/api/casino/dice")
public class DiceApiController {

    @Autowired
    private PersonJpaRepository personJpaRepository;
    

    @Getter 
    public static class DiceRequest {
        private double winChance;
        private double betSize;
        private String uid;
    }


    @PostMapping("/calculate")
    public ResponseEntity<Double> postDice(@RequestBody DiceRequest diceRequest) {
        System.out.println("Received request: " + diceRequest);
        Dice dice = new Dice(diceRequest.getWinChance(), diceRequest.getBetSize());
        System.out.println(diceRequest.getUid());
        
        Person user = personJpaRepository.findByUid(diceRequest.getUid());
        if (diceRequest.getBetSize() > user.getBalanceDouble()){
            return new ResponseEntity<>(user.getBalanceDouble(), HttpStatus.NOT_ACCEPTABLE);
        }

        double currentBalance = user.getBalanceDouble();
        System.out.println(user.getBalanceDouble());
        double updatedBalance = currentBalance + dice.calculateWin();
        user.setBalanceString(updatedBalance, "dice");
        personJpaRepository.save(user);  // Save the updated balance

        return new ResponseEntity<>(user.getBalanceDouble(), HttpStatus.OK);  // Return updated user data
    }

}
