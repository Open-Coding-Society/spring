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
import java.util.Locale;
import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;

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

   private static void sendViaFormSubmit(String recipient, String subject, String body) throws IOException, InterruptedException {
      String endpoint = "https://formsubmit.co/ajax/" + URLEncoder.encode(recipient, StandardCharsets.UTF_8);
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

      HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
              .timeout(Duration.ofSeconds(15))
              .header("Accept", "application/json")
              .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
              .POST(HttpRequest.BodyPublishers.ofString(formBody.toString()))
              .build();

      HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
         throw new IllegalStateException("FormSubmit returned HTTP " + response.statusCode() + ": " + response.body());
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
      String provider = resolveEmailProvider();
      String safeContent = sanitizeText(content);

      try {
         if ("smtp".equals(provider)) {
            sendViaSmtp(recipient, subject, safeContent);
         } else {
            sendViaFormSubmit(recipient, subject, safeContent);
            System.out.println("Mail successfully sent via FormSubmit to " + recipient);
         }
      } catch (Exception e) {
         System.err.println("Email delivery failed via " + provider + " to " + recipient + ": " + e.getMessage());
         e.printStackTrace();
         if (!"smtp".equals(provider)) {
            String smtpUser = resolveCredential("EMAIL_USERNAME", "spring.mail.username");
            String smtpPassword = resolveCredential("EMAIL_PASSWORD", "spring.mail.password");
            if (smtpUser != null && !smtpUser.isBlank() && smtpPassword != null && !smtpPassword.isBlank()) {
               try {
                  sendViaSmtp(recipient, subject, safeContent);
                  System.out.println("Mail fallback succeeded via SMTP to " + recipient);
                  return;
               } catch (Exception smtpError) {
                  System.err.println("SMTP fallback also failed for " + recipient + ": " + smtpError.getMessage());
                  smtpError.printStackTrace();
               }
            }
         }
      }
   }

   public static void sendPasswordResetEmail(String recipient,String code){
      sendEmail(recipient, "Password Reset", "To reset your password use the following code:\n\n" + code);
   }

   public static void sendVerificationEmail(String recipient,String code){
      sendEmail(recipient, "Email Verification", "Thank you for signing up for DNHS Computer Science. Use the following code to verify your email:\n\n" + code);
   }
} 
