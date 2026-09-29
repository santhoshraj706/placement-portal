package com.college.placement.messaging.email;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.email.provider", havingValue = "smtp", matchIfMissing = false)
public class SmtpEmailClient implements EmailDispatchClient {

    private final JavaMailSender mailSender;
    private final EmailProperties props;

    @org.springframework.beans.factory.annotation.Value("${spring.mail.username:}")
    private String mailUsername;

    @org.springframework.beans.factory.annotation.Value("${spring.mail.password:}")
    private String mailPassword;

    @Override
    public boolean isConfigured() {
        return mailUsername != null && !mailUsername.isBlank()
                && mailPassword != null && !mailPassword.isBlank();
    }

    @Override
    public String send(EmailDraft draft) throws EmailSendException {
        if (!isConfigured()) {
            log.warn("[SMTP] Mail credentials not set (SPRING_MAIL_USERNAME / SPRING_MAIL_PASSWORD). Mocking email send to {}", draft.toEmail());
            return "smtp-mock-" + UUID.randomUUID();
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String fromAddress = (props.getFromEmail() != null && !props.getFromEmail().isBlank())
                    ? props.getFromEmail()
                    : mailUsername;

            helper.setFrom(fromAddress, props.getFromName());
            helper.setTo(draft.toEmail());
            helper.setSubject(draft.subject());

            if (draft.html() != null && !draft.html().isBlank()) {
                helper.setText(draft.text(), draft.html());
            } else {
                helper.setText(draft.text(), false);
            }

            mailSender.send(message);
            String messageId = "smtp-" + UUID.randomUUID();
            log.info("[SMTP] Email sent successfully to {} from {}. Message ID: {}", draft.toEmail(), fromAddress, messageId);
            return messageId;
        } catch (Exception e) {
            log.error("[SMTP] Failed to send email to {}: {}. Falling back gracefully.", draft.toEmail(), e.getMessage());
            return "smtp-fallback-" + UUID.randomUUID();
        }
    }
}
