package com.open.spring.mvc.slack;

import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.open.spring.mvc.comment.Comment;
import com.open.spring.mvc.comment.CommentJPA;
import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.person.Email.Email;

import io.github.cdimascio.dotenv.Dotenv;
import java.util.List;

@Service
public class EmailNotificationService {

    private static final Properties APPLICATION_PROPERTIES = loadApplicationProperties();
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Autowired
    private PersonJpaRepository personRepository;

    @Autowired
    private CommentJPA commentJPA;

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

    private boolean isLikelyEmail(String value) {
        return value != null && EMAIL_PATTERN.matcher(value.trim()).matches();
    }

    private String resolveMappedOwnerEmail(String ownerUid) {
        String mapping = resolveValue("ISSUE_OWNER_EMAIL_MAP", "issue.owner.email-map");
        if (mapping == null || mapping.isBlank() || ownerUid == null || ownerUid.isBlank()) {
            return null;
        }

        for (String pair : mapping.split(",")) {
            String[] entry = pair.split(":", 2);
            if (entry.length != 2) {
                continue;
            }

            String key = entry[0] == null ? "" : entry[0].trim();
            String value = entry[1] == null ? "" : entry[1].trim();
            if (!key.isEmpty() && key.equalsIgnoreCase(ownerUid) && isLikelyEmail(value)) {
                return value;
            }
        }

        return null;
    }

    private String resolveIssueOwnerEmail(String ownerUid) {
        if (ownerUid == null || ownerUid.isBlank()) {
            return null;
        }

        String ownerKey = ownerUid.trim();

        Person byUid = personRepository.findByUid(ownerKey);
        if (byUid != null && byUid.getEmail() != null && !byUid.getEmail().isBlank()) {
            return byUid.getEmail();
        }

        if (isLikelyEmail(ownerKey)) {
            return ownerKey;
        }

        Person byName = personRepository.findByName(ownerKey);
        if (byName != null && byName.getEmail() != null && !byName.getEmail().isBlank()) {
            return byName.getEmail();
        }

        return resolveMappedOwnerEmail(ownerKey);
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

        String ownerUid = issue.getOwnerUid();
        System.out.println("[EmailNotificationService] Issue ID: " + issue.getId() + ", Owner UID from issue: " + ownerUid);

        // Pull recipient email ONLY from database
        String recipientEmail = resolveIssueOwnerEmail(ownerUid);
        System.out.println("[EmailNotificationService] Resolved owner email from database: " + recipientEmail);

        // Don't fallback to .env - if owner email not in database, skip
        if (recipientEmail == null || recipientEmail.isBlank()) {
            System.out.println("[EmailNotificationService] NO RECIPIENT EMAIL IN DATABASE for owner " + ownerUid + " - skipping send");
            return;
        }

        String issueTitle = issue.getTitle() == null ? "your issue" : issue.getTitle();
        String subject = "Open Coding Society: new comment on " + issueTitle;
        String body = new StringBuilder()
                .append(comment.getAuthor()).append(" commented on an issue you created:\n\n")
                .append(issueTitle).append("\n")
                .append("Issue ID: ").append(issue.getId()).append("\n\n")
                .append(comment.getText())
                .toString();

        try {
            System.out.println("[EmailNotificationService] SENDING email to " + recipientEmail + " for issue " + issue.getId());
            Email.sendEmail(recipientEmail, subject, body);
            System.out.println("[EmailNotificationService] SUCCESSFULLY SENT to " + recipientEmail + " for issue " + issue.getId());
        } catch (Exception ex) {
            System.err.println("[EmailNotificationService] FAILED to send to " + recipientEmail + " for issue " + issue.getId() + ": " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    /**
     * Notify all users who starred an issue when a new comment is posted
     */
    public void notifyAllStarredIssueFollowers(CalendarIssue issue, Comment comment) {
        if (issue == null || issue.getId() == null) {
            return;
        }

        // Get all "stars" for this issue (use same assignment key format as CommentApiController)
        String starAssignmentKey = "issue-" + issue.getId() + "::star";
        List<Comment> starComments = commentJPA.findByAssignmentAndText(starAssignmentKey, "star");

        if (starComments == null || starComments.isEmpty()) {
            System.out.println("[EmailNotificationService] No users have starred issue " + issue.getId());
            return;
        }

        String issueTitle = issue.getTitle() == null ? "an issue" : issue.getTitle();
        String subject = "Open Coding Society: new reply on " + issueTitle + " (you starred this)";

        for (Comment starComment : starComments) {
            String starrerId = starComment.getAuthor();
            if (starrerId == null || starrerId.equals(comment.getAuthor())) {
                // Don't send to the person who posted the comment
                continue;
            }

            Person starrer = personRepository.findByUid(starrerId);
            if (starrer == null || starrer.getEmail() == null || starrer.getEmail().isBlank()) {
                System.out.println("[EmailNotificationService] Starrer " + starrerId + " has no email in database");
                continue;
            }

            String recipientEmail = starrer.getEmail();
            String body = new StringBuilder()
                    .append(comment.getAuthor()).append(" replied to an issue you starred:\\n\\n")
                    .append(issueTitle).append("\\n")
                    .append("Issue ID: ").append(issue.getId()).append("\\n\\n")
                    .append(comment.getText())
                    .toString();

            try {
                System.out.println("[EmailNotificationService] SENDING to starrer " + recipientEmail + " for issue " + issue.getId());
                Email.sendEmail(recipientEmail, subject, body);
                System.out.println("[EmailNotificationService] SUCCESS - starrer notified: " + recipientEmail);
            } catch (Exception ex) {
                System.err.println("[EmailNotificationService] FAILED to notify starrer " + recipientEmail + ": " + ex.getMessage());
                ex.printStackTrace();
            }
        }
    }
}
