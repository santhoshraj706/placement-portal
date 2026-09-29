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

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public String send(EmailDraft draft) throws EmailSendException {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String fromAddress = (props.getFromEmail() != null && !props.getFromEmail().isBlank())
                    ? props.getFromEmail()
                    : "devlopers36@gmail.com";

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
            log.error("[SMTP] Failed to send email to {}", draft.toEmail(), e);
            throw new EmailSendException(EmailSendException.Category.SERVER, "SMTP email delivery failed: " + e.getMessage(), false);
        }
    }
}
