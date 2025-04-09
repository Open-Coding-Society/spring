
package com.nighthawk.spring_portfolio.mvc.blackjack;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;

import io.jsonwebtoken.lang.Arrays;

@RestController
@RequestMapping("/api/casino/blackjack")
public class BlackjackApiController {

    private static final Logger LOGGER = Logger.getLogger(BlackjackApiController.class.getName());

    @Autowired
    private BlackjackJpaRepository repository;

    @Autowired
    private PersonJpaRepository personJpaRepository;

    /** 
     * Start a new Blackjack game
     */
    @PostMapping("/start")
    public ResponseEntity<Blackjack> startGame(@RequestBody Map<String, Object> request) {
        try {
            String uid = request.get("uid").toString();
            double betAmount = Double.parseDouble(request.get("betAmount").toString());

            Person person = personJpaRepository.findByUid(uid);
            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            Blackjack game = new Blackjack();
            game.setPerson(person);
            game.setStatus("ACTIVE");
            game.setBetAmount(betAmount);
            game.initializeDeck();
            game.dealInitialHands();

            repository.save(game);
            return ResponseEntity.ok(game);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error starting game", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    /** 
     * Handle "Hit" action
     */
    @PostMapping("/hit")
    public ResponseEntity<Object> hit(@RequestBody Map<String, Object> request) {
        try {
            String uid = request.get("uid").toString();
            Person person = personJpaRepository.findByUid(uid);

            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Person not found");
            }

            Optional<Blackjack> optionalGame = repository.findFirstByPersonAndStatusOrderByIdDesc(person, "ACTIVE");
            if (optionalGame.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No active game found");
            }

            Blackjack game = optionalGame.get();
            game.loadGameState();

            List<String> playerHand = (List<String>) game.getGameStateMap().get("playerHand");
            List<String> deck = (List<String>) game.getGameStateMap().get("deck");

            if (deck == null || deck.isEmpty()) {
                return ResponseEntity.ok("Deck is empty");
            }

            String drawnCard = deck.remove(0);
            playerHand.add(drawnCard);
            int playerScore = game.calculateScore(playerHand);

            game.getGameStateMap().put("playerHand", playerHand);
            game.getGameStateMap().put("playerScore", playerScore);
            game.getGameStateMap().put("deck", deck);
            game.persistGameState();

            if (playerScore > 21) {
                double updatedBalance = person.getBalanceDouble() - game.getBetAmount();
                person.setBalanceString(updatedBalance);
                game.setStatus("INACTIVE");
                personJpaRepository.save(person);
            }

            repository.save(game);
            return ResponseEntity.ok(game);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing hit", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal server error");
        }
    }

    /** 
     * Handle "Stand" action
     */
    @PostMapping("/stand")
    public ResponseEntity<Object> stand(@RequestBody Map<String, Object> request) {
        try {
            String uid = request.get("uid").toString();
            Person person = personJpaRepository.findByUid(uid);

            if (person == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Person not found");
            }

            Optional<Blackjack> optionalGame = repository.findFirstByPersonAndStatusOrderByIdDesc(person, "ACTIVE");
            if (optionalGame.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No active game found");
            }

            Blackjack game = optionalGame.get();
            game.loadGameState();

            List<String> dealerHand = (List<String>) game.getGameStateMap().get("dealerHand");
            List<String> deck = (List<String>) game.getGameStateMap().get("deck");
            int playerScore = (int) game.getGameStateMap().getOrDefault("playerScore", 0);
            int dealerScore = (int) game.getGameStateMap().getOrDefault("dealerScore", 0);
            double betAmount = game.getBetAmount();

            while (dealerScore < 17 && deck != null && !deck.isEmpty()) {
                String drawnCard = deck.remove(0);
                dealerHand.add(drawnCard);
                dealerScore = game.calculateScore(dealerHand);
            }

            game.getGameStateMap().put("dealerHand", dealerHand);
            game.getGameStateMap().put("dealerScore", dealerScore);
            game.getGameStateMap().put("deck", deck);

            // Determine game outcome
            String result;
            if (playerScore > 21) {
                result = "LOSE";
                double updatedBalance = person.getBalanceDouble() - betAmount;
                person.setBalanceString(updatedBalance);
            } else if (dealerScore > 21 || playerScore > dealerScore) {
                result = "WIN";
                double updatedBalance = person.getBalanceDouble() + betAmount;
                person.setBalanceString(updatedBalance);
            } else if (playerScore < dealerScore) {
                result = "LOSE";
                double updatedBalance = person.getBalanceDouble() - betAmount;
                person.setBalanceString(updatedBalance);
            } else {
                result = "DRAW";
            }

            game.getGameStateMap().put("result", result);
            game.setStatus("INACTIVE");
            repository.save(game);
            personJpaRepository.save(person);

            return ResponseEntity.ok(game);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing stand", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal server error");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> safeCastToList(Object obj) {
        if (obj instanceof List) {
            return (List<String>) obj;
        }
        return Arrays.asList(new String[0]);
    }
}