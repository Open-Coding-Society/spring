package com.nighthawk.spring_portfolio.mvc.Slack;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class SlackService {

    // Slack Incoming Webhook URL (replace this with your actual webhook URL)
    private static final String SLACK_WEBHOOK_URL = "https://hooks.slack.com/services/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";

    // Method to send a message to Slack
    public void sendMessage(String message) {
        // Create the JSON payload for the Slack message
        String payload = "{\"text\":\"" + message + "\"}";

        // Set up RestTemplate and headers
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Send the message to Slack
        HttpEntity<String> entity = new HttpEntity<>(payload, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(SLACK_WEBHOOK_URL, entity, String.class);

        // Check the response from Slack (optional)
        if (response.getStatusCode().is2xxSuccessful()) {
            System.out.println("Message sent to Slack successfully.");
        } else {
            System.out.println("Failed to send message to Slack.");
        }
    }
}
