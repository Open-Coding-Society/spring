package com.open.spring.mvc.person.Email;


// Java program to send email 
  
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import org.json.JSONObject;

//dot env for email username/password
import io.github.cdimascio.dotenv.Dotenv;
  
public class Email  
{ 

   private static final Properties APPLICATION_PROPERTIES = loadApplicationProperties();
   private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
           .connectTimeout(Duration.ofSeconds(10))
           .build();

   private static Properties loadApplicationProperties() {
      Properties props = new Properties();
      try (InputStream input = Email.class.getClassLoader().getResourceAsStream("application.properties")) {
         if (input != null) {
            props.load(input);
         }
      } catch (IOException e) {
         // Fall back to env/system properties if classpath properties cannot be loaded.
      }
      return props;
   }

   private static String resolveCredential(String key, String applicationKey) {
      String value = System.getProperty(key);
      if (value != null && !value.isBlank()) {
         return value;
      }

      value = System.getenv(key);
      if (value != null && !value.isBlank()) {
         return value;
      }

      try {
         final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
         value = dotenv.get(key);
         if (value != null && !value.isBlank()) {
            return value;
         }
      } catch (Exception e) {
         // Ignore and fall back to packaged properties.
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

   private static String resolveEmailProvider() {
      String provider = resolveCredential("EMAIL_PROVIDER", "email.provider");
      return provider == null || provider.isBlank() ? "formsubmit" : provider.trim().toLowerCase(Locale.ROOT);
   }

   private static String sanitizeText(String input) {
      if (input == null || input.isBlank()) {
         return "";
      }

      return input.replace("\r\n", "\n")
              .replace("\r", "\n")
              .replaceAll("(?i)<br\\s*/?>", "\n")
              .replaceAll("(?i)</p>", "\n\n")
              .replaceAll("(?i)</div>", "\n")
              .replaceAll("<[^>]+>", "")
              .replace("&nbsp;", " ")
              .trim();
   }

   private static String multipartToText(Multipart multipart) throws MessagingException, IOException {
      if (multipart == null) {
         return "";
      }

      StringBuilder body = new StringBuilder();
      for (int i = 0; i < multipart.getCount(); i++) {
         Object content = multipart.getBodyPart(i).getContent();
         if (content == null) {
            continue;
         }
         if (body.length() > 0) {
            body.append("\n");
         }
         body.append(sanitizeText(content.toString()));
      }
      return body.toString();
   }

   private static void addField(StringBuilder builder, String key, String value) {
      if (value == null) {
         return;
      }
      if (builder.length() > 0) {
         builder.append('&');
      }
      builder.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
      builder.append('=');
      builder.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
   }

   private static String resolveFormSubmitEndpoint(String recipient) {
      String configured = resolveCredential("FORM_SUBMIT_ENDPOINT", "formsubmit.endpoint");
      if (configured == null || configured.isBlank()) {
         configured = "https://formsubmit.co/ajax/{recipient}";
      } else {
         // Expand simple placeholder of form ${VAR:default} if present in properties
         if (configured.startsWith("${") && configured.endsWith("}")) {
            int colon = configured.indexOf(':', 2);
            if (colon > 2) {
               String varName = configured.substring(2, colon);
               String defaultVal = configured.substring(colon + 1, configured.length() - 1);
               String resolved = resolveCredential(varName, null);
               if (resolved != null && !resolved.isBlank()) {
                  configured = resolved;
               } else {
                  configured = defaultVal;
               }
            }
         }
      }

      String encodedRecipient = URLEncoder.encode(recipient, StandardCharsets.UTF_8);
      if (configured.contains("{recipient}")) {
         return configured.replace("{recipient}", encodedRecipient);
      }

      if (configured.endsWith("/")) {
         return configured + encodedRecipient;
      }

      return configured + "/" + encodedRecipient;
   }

   private static void sendViaFormSubmit(String recipient, String subject, String body) throws IOException, InterruptedException {
      if (recipient == null || recipient.isBlank()) {
         throw new IllegalArgumentException("Recipient is required for FormSubmit delivery.");
      }

      String endpoint = resolveFormSubmitEndpoint(recipient);
      String sender = resolveCredential("EMAIL_USERNAME", "spring.mail.username");
      String replyTo = resolveCredential("EMAIL_REPLY_TO", "email.replyTo");

      StringBuilder formBody = new StringBuilder();
      addField(formBody, "name", "Open Coding Society");
      addField(formBody, "_subject", subject);
      addField(formBody, "message", sanitizeText(body));
      addField(formBody, "_captcha", "false");
      addField(formBody, "_template", "table");
      if (sender != null && !sender.isBlank()) {
         addField(formBody, "email", sender);
         addField(formBody, "_replyto", sender);
      }
      if (replyTo != null && !replyTo.isBlank()) {
         addField(formBody, "_replyto", replyTo);
      }

      String origin = resolveCredential("FORM_SUBMIT_ORIGIN", "formsubmit.origin");
      if (origin == null || origin.isBlank()) {
         origin = "https://pages.opencodingsociety.com";
      }
      String referer = resolveCredential("FORM_SUBMIT_REFERER", "formsubmit.referer");
      if (referer == null || referer.isBlank()) {
         referer = origin + "/";
      }

      HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(15))
              .header("Accept", "application/json")
              .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
              .header("User-Agent", "open-coding-society-email-service")
              .header("Origin", origin)
              .header("Referer", referer)
              .POST(HttpRequest.BodyPublishers.ofString(formBody.toString()))
              .build();

      System.out.println("[Email] FormSubmit POST -> " + endpoint + " (Origin: " + origin + ", Referer: " + referer + ")");
      System.out.println("[Email] Form body: " + formBody.toString());

      HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      System.out.println("[Email] FormSubmit response HTTP " + response.statusCode());
      String responseBody = response.body();
      System.out.println("[Email] FormSubmit response body: " + (responseBody == null ? "" : responseBody));

      if (response.statusCode() < 200 || response.statusCode() >= 300) {
         throw new IllegalStateException("FormSubmit returned HTTP " + response.statusCode() + ": " + response.body());
      }

      try {
         JSONObject json = new JSONObject(responseBody == null ? "{}" : responseBody);
         String success = json.optString("success", "").trim().toLowerCase(Locale.ROOT);
         if ("false".equals(success)) {
            String message = json.optString("message", "FormSubmit rejected the request.");
            throw new IllegalStateException("FormSubmit rejected delivery: " + message);
         }
      } catch (org.json.JSONException parseError) {
         // Accept non-JSON success responses from provider/CDN edge behavior.
      }
   }

   private static void sendViaSmtp(String recipient, String subject, String body) {
      String sender = resolveCredential("EMAIL_USERNAME", "spring.mail.username");
      String password = resolveCredential("EMAIL_PASSWORD", "spring.mail.password");
      String smtpHost = resolveCredential("EMAIL_SMTP_HOST", "spring.mail.host");
      String smtpPort = resolveCredential("EMAIL_SMTP_PORT", "spring.mail.port");

      if (sender == null || password == null) {
         throw new IllegalStateException("Email credentials are not configured. Set EMAIL_USERNAME and EMAIL_PASSWORD or spring.mail.username/password.");
      }

      java.util.Properties properties = System.getProperties();
      properties.put("mail.smtp.auth", "true");
      properties.put("mail.smtp.starttls.enable", "true");
      properties.put("mail.smtp.host", smtpHost != null && !smtpHost.isBlank() ? smtpHost : "smtp.gmail.com");
      properties.put("mail.smtp.port", smtpPort != null && !smtpPort.isBlank() ? smtpPort : "587");
      properties.put("mail.smtp.ssl.protocols", "TLSv1.2");

      jakarta.mail.Session session = jakarta.mail.Session.getDefaultInstance(properties, new jakarta.mail.Authenticator() {
        @Override
        protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
            return new jakarta.mail.PasswordAuthentication(sender, password);
        }
      });

      try {
         jakarta.mail.internet.MimeMessage message = new jakarta.mail.internet.MimeMessage(session);
         message.setFrom(new jakarta.mail.internet.InternetAddress(sender));
         message.addRecipient(jakarta.mail.Message.RecipientType.TO, new jakarta.mail.internet.InternetAddress(recipient));
         message.setSubject(subject);
         message.setContent(body, "text/plain; charset=UTF-8");
         jakarta.mail.Transport.send(message);
         System.out.println("Mail successfully sent");
      } catch (MessagingException mex) {
         throw new IllegalStateException("SMTP delivery failed", mex);
      }
   }
  
   public static void sendEmail(String recipient, String subject, Multipart multipart){
      try {
         sendEmail(recipient, subject, multipartToText(multipart));
      } catch (MessagingException | IOException e) {
         throw new IllegalStateException("Unable to prepare email content", e);
      }
   }

   public static void sendEmail(String recipient, String subject, String content){
      // Use FormSubmit for deployment safety - no server credentials needed
      String safeContent = sanitizeText(content);

      try {
         System.out.println("[Email] Sending via FormSubmit to " + recipient);
         sendViaFormSubmit(recipient, subject, safeContent);
         System.out.println("[Email] SUCCESS via FormSubmit to " + recipient);
      } catch (Exception e) {
         System.err.println("[Email] FAILED via FormSubmit to " + recipient + ": " + e.getMessage());
         e.printStackTrace();
         throw new RuntimeException("Email delivery failed: " + e.getMessage(), e);
      }
   }

   public static Map<String, String> sendEmailViaSmtp(List<String> recipients, String subject, String body) {
      if (recipients == null || recipients.isEmpty()) {
         throw new IllegalArgumentException("At least one recipient is required.");
      }

      String safeBody = sanitizeText(body);
      Map<String, String> results = new LinkedHashMap<>();
      for (String recipient : recipients) {
         if (recipient == null || recipient.isBlank()) {
            results.put(String.valueOf(recipient), "error: blank recipient");
            continue;
         }
         try {
            System.out.println("[Email] Sending via SMTP to " + recipient);
            sendViaSmtp(recipient.trim(), subject, safeBody);
            System.out.println("[Email] SUCCESS via SMTP to " + recipient);
            results.put(recipient, "sent");
         } catch (Exception e) {
            System.err.println("[Email] FAILED via SMTP to " + recipient + ": " + e.getMessage());
            results.put(recipient, "error: " + e.getMessage());
         }
      }
      return results;
   }

   public static void sendPasswordResetEmail(String recipient,String code){
      sendEmail(recipient, "Password Reset", "To reset your password use the following code:\n\n" + code);
   }

   public static void sendVerificationEmail(String recipient,String code){
      sendEmail(recipient, "Email Verification", "Thank you for signing up for DNHS Computer Science. Use the following code to verify your email:\n\n" + code);
   }
} 
