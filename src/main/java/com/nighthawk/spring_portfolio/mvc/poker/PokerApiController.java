package com.nighthawk.spring_portfolio.mvc.poker;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nighthawk.spring_portfolio.mvc.person.Person;
import com.nighthawk.spring_portfolio.mvc.person.PersonJpaRepository;

@RestController
@RequestMapping("/api/casino/poker")
public class PokerApiController {

    @Autowired
    private PersonJpaRepository personJpaRepository;

    private PokerBoard pokerBoard;

    // Request class to receive bet and uid from the client
    public static class PokerRequest {
        private double bet;
        private String uid;

        public double getBet() { return bet; }
        public void setBet(double bet) { this.bet = bet; }

        public String getUid() { return uid; }
        public void setUid(String uid) { this.uid = uid; }
    }

    // Response class to send game results back to the client
    public static class PokerResponse {
        private List<PokerCard> playerHand;
        private List<PokerCard> dealerHand;
        private double updatedBalance;
        private boolean playerWin;
        private double bet;

        public PokerResponse(List<PokerCard> playerHand, List<PokerCard> dealerHand, double updatedBalance, boolean playerWin, double bet) {
            this.playerHand = playerHand;
            this.dealerHand = dealerHand;
            this.updatedBalance = updatedBalance;
            this.playerWin = playerWin;
            this.bet = bet;
        }

        public List<PokerCard> getPlayerHand() { return playerHand; }
        public List<PokerCard> getDealerHand() { return dealerHand; }
        public double getUpdatedBalance() { return updatedBalance; }
        public boolean isPlayerWin() { return playerWin; }
        public double getBet() { return bet; }
    }

    @PostMapping("/play")
    public ResponseEntity<?> playGame(@RequestBody PokerRequest pokerRequest) {
        if (pokerBoard == null) {
            pokerBoard = new PokerBoard();  // Initialize if not done
        }

        // Fetch person by uid
        Person person = personJpaRepository.findByUid(pokerRequest.getUid());
        if (person == null) {
            return new ResponseEntity<>("User not found.", HttpStatus.NOT_FOUND);  // Person not found
        }

        double currentBalance = person.getBalanceDouble();
        double bet = pokerRequest.getBet();

        // Check if the player has enough balance to place the bet
        if (bet > currentBalance) {
            return new ResponseEntity<>("Insufficient balance to place bet.", HttpStatus.BAD_REQUEST);
        }

        // Deal hands for the game
        pokerBoard.dealHands();

        // Calculate win/loss and update person balance
        PokerGameResult result = new PokerGameResult(pokerBoard.getPlayerHand(), pokerBoard.getDealerHand(), bet);
        boolean playerWin = result.isPlayerWin();
        double winnings = playerWin ? bet : -bet;
        double updatedBalance = currentBalance + winnings;

        // Update the person's balance in the database
        person.setBalanceString(updatedBalance, "poker");
        personJpaRepository.save(person);

        // Create and return the response object with game results and updated balance
        PokerResponse response = new PokerResponse(
            pokerBoard.getPlayerHand(),
            pokerBoard.getDealerHand(),
            updatedBalance,
            playerWin,
            bet
        );

        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    // To reset the board
    @PostMapping("/reset")
    public ResponseEntity<String> resetGame() {
        pokerBoard = new PokerBoard();
        return new ResponseEntity<>("Game has been reset.", HttpStatus.OK);
    }
}
