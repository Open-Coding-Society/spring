package com.open.spring.mvc.slack;

import java.util.Map;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.comment.Comment;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.person.Email.Email;

import io.github.cdimascio.dotenv.Dotenv;

@Service
public class EmailNotificationService {

    private static final Properties APPLICATION_PROPERTIES = loadApplicationProperties();

    @Autowired
    private PersonJpaRepository personRepository;

    private static Properties loadApplicationProperties() {
        Properties props = new Properties();
        try (var input = EmailNotificationService.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                props.load(input);
            }
        } catch (Exception e) {
            // fall back to env/system properties
        }
        return props;
    }

    private String resolveValue(String key, String applicationKey) {
        String value = System.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }

        value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            return value;
        }

        try {
            Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
            value = dotenv.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        } catch (Exception e) {
            // ignore and fall back to packaged properties
        }

        value = APPLICATION_PROPERTIES.getProperty(key);
        if (value != null && !value.isBlank()) {
            return value;
        }

        if (applicationKey != null && !applicationKey.isBlank()) {
            value = APPLICATION_PROPERTIES.getProperty(applicationKey);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        return null;
    }

    String resolveRecipientEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            Person authenticatedPerson = personRepository.findByUid(authentication.getName());
            if (authenticatedPerson != null && authenticatedPerson.getEmail() != null && !authenticatedPerson.getEmail().isBlank()) {
                return authenticatedPerson.getEmail();
            }
        }

        String forwardPersonUid = resolveValue("FORWARD_PERSON_UID", null);
        if (forwardPersonUid != null && !forwardPersonUid.isBlank()) {
            Person person = personRepository.findByUid(forwardPersonUid);
            if (person != null && person.getEmail() != null && !person.getEmail().isBlank()) {
                return person.getEmail();
            }
        }

        String forwardEmail = resolveValue("FORWARD_EMAIL", null);
        if (forwardEmail != null && !forwardEmail.isBlank()) {
            return forwardEmail;
        }

        return resolveValue("EMAIL_USERNAME", "spring.mail.username");
    }

    /**
     * Notify configured recipient(s) about a Slack message.
     * Priority: logged-in person's email -> FORWARD_PERSON_UID -> FORWARD_EMAIL -> SMTP username -> no-op
     */
    public void notifyOnSlackMessage(Map<String, String> messageData) {
        String recipient = resolveRecipientEmail();

        if (recipient == null) {
            // Nothing configured; don't create new systems or spam logs
            return;
        }

        // Build a concise subject and body
        String subject = "Open Coding Society: new Slack message";
        StringBuilder body = new StringBuilder();
        body.append("A new Slack event was received:\n\n");
        for (Map.Entry<String, String> e : messageData.entrySet()) {
            body.append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }

        // Use existing Email utility to send the notification
        try {
            Email.sendEmail(recipient, subject, body.toString());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void notifyOnIssueComment(CalendarIssue issue, Comment comment) {
        if (issue == null || comment == null) {
            return;
        }

        String issueOwnerUid = issue.getOwnerUid();
        if (issueOwnerUid == null || issueOwnerUid.isBlank()) {
            return;
        }

        if (issueOwnerUid.equals(comment.getAuthor())) {
            return;
        }

        Person recipient = personRepository.findByUid(issueOwnerUid);
        if (recipient == null || recipient.getEmail() == null || recipient.getEmail().isBlank()) {
            return;
        }

        String issueTitle = issue.getTitle() == null ? "your issue" : issue.getTitle();
        String subject = "Open Coding Society: new comment on " + issueTitle;
        String body = new StringBuilder()
                .append(comment.getAuthor()).append(" commented on your issue:\n\n")
                .append(issueTitle).append("\n")
                .append("Issue ID: ").append(issue.getId()).append("\n\n")
                .append(comment.getText())
                .toString();

        try {
            Email.sendEmail(recipient.getEmail(), subject, body);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
