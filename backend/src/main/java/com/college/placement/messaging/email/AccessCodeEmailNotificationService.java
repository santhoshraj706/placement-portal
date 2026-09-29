package com.college.placement.messaging.email;

import com.college.placement.messaging.mongo.document.MongoAccessCodeEmailOutbox;
import com.college.placement.studentimport.dto.ImportedStudentAccess;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.mongodb.client.result.UpdateResult;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Queues and drains access-code emails for students authorized by the PO roster
 * import. Mirrors {@link DriveEmailNotificationService}: content is rendered at
 * enqueue time, the row is claimed atomically, and terminal states are monotonic.
 *
 * <p>Unlike the other outboxes the rendered content embeds a live credential, so
 * it is scrubbed from the row as soon as the provider accepts the message.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AccessCodeEmailNotificationService {

    public static final String EVENT_ACCESS_CODE_ISSUED = "ACCESS_CODE_ISSUED";

    private static final int INSERT_CHUNK = 200;
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\r\\n\\u0000-\\u001F]");

    private final EmailProperties props;
    private final MongoTemplate mongoTemplate;
    private final EmailDispatchClient dispatchClient;

    /**
     * Queues one access-code email per imported roster row.
     *
     * @return number of outbox rows queued
     */
    public int enqueueAccessCodeIssued(String batchId, List<ImportedStudentAccess> students) {
        if (!props.isEnabled()) {
            log.info("[EMAIL] Access-code notification skipped for batch {}: email notifications are disabled",
                    batchId);
            return 0;
        }
        if (students == null || students.isEmpty()) {
            return 0;
        }

        String registerUrl = props.getFrontendUrl().replaceAll("/+$", "") + "/register";
        LocalDateTime now = LocalDateTime.now();

        List<MongoAccessCodeEmailOutbox> batch = new ArrayList<>(students.size());
        int skippedInvalid = 0;
        for (ImportedStudentAccess s : students) {
            if (s == null || s.accessCodeId() == null) {
                continue;
            }
            String email = s.email() == null ? "" : s.email().trim();
            boolean valid = isValidEmail(email);
            if (!valid) {
                skippedInvalid++;
            }
            String subject = valid ? "Your Placement Portal access code" : null;
            String html = valid ? buildHtml(s, registerUrl) : null;
            String text = valid ? buildText(s, registerUrl) : null;
            batch.add(MongoAccessCodeEmailOutbox.builder()
                    .accessCodeId(s.accessCodeId())
                    .eventKey(EVENT_ACCESS_CODE_ISSUED)
                    .batchId(batchId)
                    .recipientEmail(valid ? email : null)
                    .registerNumber(s.registerNumber())
                    .subject(subject)
                    .html(html)
                    .text(text)
                    .status(valid ? EmailOutboxStatus.PENDING.name()
                            : EmailOutboxStatus.SKIPPED_INVALID_EMAIL.name())
                    .attempts(0)
                    .nextAttemptAt(valid ? now : null)
                    .lastError(valid ? null : "email missing or invalid")
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }

        int queued = insertIgnoringDuplicates(batch);
        log.info("[EMAIL] Access-code batch {} queued={} rows={} skippedInvalid={}",
                batchId, queued, students.size(), skippedInvalid);
        return queued;
    }

    private int insertIgnoringDuplicates(List<MongoAccessCodeEmailOutbox> batch) {
        int inserted = 0;
        for (int start = 0; start < batch.size(); start += INSERT_CHUNK) {
            List<MongoAccessCodeEmailOutbox> chunk =
                    batch.subList(start, Math.min(start + INSERT_CHUNK, batch.size()));
            try {
                mongoTemplate.insert(chunk, MongoAccessCodeEmailOutbox.class);
                inserted += chunk.size();
            } catch (DuplicateKeyException e) {
                for (MongoAccessCodeEmailOutbox doc : chunk) {
                    try {
                        mongoTemplate.insert(doc);
                        inserted++;
                    } catch (DuplicateKeyException alreadyQueued) {
                        // dedupe key (accessCodeId, eventKey) already exists
                    }
                }
            }
        }
        return inserted;
    }

    public void processDueBatch() {
        if (!props.isEnabled()) {
            return;
        }
        requeueStaleInflight();
        List<MongoAccessCodeEmailOutbox> claimed = claimBatch();
        if (claimed.isEmpty()) {
            return;
        }
        if (!dispatchClient.isConfigured()) {
            for (MongoAccessCodeEmailOutbox job : claimed) {
                markStatus(job, EmailOutboxStatus.CONFIG_ERROR, null, "email provider not configured");
            }
            log.info("[EMAIL] Access-code batch: submitted=0 configError={}", claimed.size());
            return;
        }

        int submitted = 0;
        int failed = 0;
        int retried = 0;
        for (MongoAccessCodeEmailOutbox job : claimed) {
            if (job.getRecipientEmail() == null || job.getRecipientEmail().isBlank()) {
                markStatus(job, EmailOutboxStatus.SKIPPED_INVALID_EMAIL, null, "recipient has no email address");
                failed++;
                continue;
            }
            if (job.getSubject() == null || job.getHtml() == null || job.getText() == null) {
                markStatus(job, EmailOutboxStatus.FAILED, null, "queued notification is missing its content");
                failed++;
                continue;
            }
            EmailDraft draft = new EmailDraft(null, null, job.getRecipientEmail(),
                    job.getSubject(), job.getHtml(), job.getText());
            try {
                String providerMessageId = dispatchClient.send(draft);
                markStatus(job, EmailOutboxStatus.SUBMITTED, providerMessageId, null);
                submitted++;
            } catch (EmailSendException ex) {
                int used = job.getAttempts() == null ? 1 : job.getAttempts();
                if (ex.isPermanent() || used >= props.getMaxAttempts()) {
                    markStatus(job, EmailOutboxStatus.FAILED,
                            null, sanitize(ex.getCategory().name() + ": " + ex.getMessage()));
                    failed++;
                } else {
                    markForRetry(job);
                    retried++;
                }
            } catch (RuntimeException ex) {
                markStatus(job, EmailOutboxStatus.FAILED,
                        null, sanitize("UNEXPECTED: " + ex.getClass().getSimpleName() + ": " + ex.getMessage()));
                failed++;
            }
        }
        log.info("[EMAIL] Access-code batch: submitted={} failed={} retried={}", submitted, failed, retried);
    }

    private List<MongoAccessCodeEmailOutbox> claimBatch() {
        Query query = new Query(Criteria.where("status").is(EmailOutboxStatus.PENDING.name())
                .and("nextAttemptAt").lte(LocalDateTime.now()));
        query.with(Sort.by(Sort.Direction.ASC, "nextAttemptAt"));
        query.limit(props.getBatchSize());
        List<MongoAccessCodeEmailOutbox> candidates =
                mongoTemplate.find(query, MongoAccessCodeEmailOutbox.class);

        List<MongoAccessCodeEmailOutbox> claimed = new ArrayList<>();
        for (MongoAccessCodeEmailOutbox candidate : candidates) {
            if (candidate.getAttempts() != null && candidate.getAttempts() >= props.getMaxAttempts()) {
                markStatus(candidate, EmailOutboxStatus.FAILED, null, "max attempts reached");
                continue;
            }
            Query claimQuery = new Query(Criteria.where("id").is(candidate.getId())
                    .and("status").is(EmailOutboxStatus.PENDING.name()));
            int nextAttempt = (candidate.getAttempts() == null ? 0 : candidate.getAttempts()) + 1;
            LocalDateTime now = LocalDateTime.now();
            UpdateResult result = mongoTemplate.updateFirst(claimQuery, new Update()
                            .set("status", EmailOutboxStatus.IN_FLIGHT.name())
                            .set("attempts", nextAttempt)
                            .set("startedAt", now)
                            .set("updatedAt", now),
                    MongoAccessCodeEmailOutbox.class);
            if (result.getModifiedCount() == 1) {
                candidate.setStatus(EmailOutboxStatus.IN_FLIGHT.name());
                candidate.setAttempts(nextAttempt);
                candidate.setStartedAt(now);
                claimed.add(candidate);
            }
        }
        return claimed;
    }

    private void requeueStaleInflight() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(props.getInflightTimeoutSeconds());
        long requeued = mongoTemplate.updateMulti(
                new Query(Criteria.where("status").is(EmailOutboxStatus.IN_FLIGHT.name())
                        .and("startedAt").lt(cutoff)),
                new Update().set("status", EmailOutboxStatus.PENDING.name())
                        .set("nextAttemptAt", LocalDateTime.now())
                        .set("startedAt", null)
                        .set("updatedAt", LocalDateTime.now()),
                MongoAccessCodeEmailOutbox.class).getModifiedCount();
        if (requeued > 0) {
            log.info("[EMAIL] Requeued {} stale in-flight access-code jobs", requeued);
        }
    }

    private void markForRetry(MongoAccessCodeEmailOutbox job) {
        LocalDateTime next = LocalDateTime.now().plusSeconds(
                props.getRetryDelayBaseSeconds() * (job.getAttempts() == null ? 0 : job.getAttempts()));
        mongoTemplate.updateFirst(
                new Query(Criteria.where("id").is(job.getId())
                        .and("status").is(EmailOutboxStatus.IN_FLIGHT.name())),
                new Update().set("status", EmailOutboxStatus.PENDING.name())
                        .set("nextAttemptAt", next)
                        .set("startedAt", null)
                        .set("updatedAt", LocalDateTime.now())
                        .set("lastError", "delivery failed, will retry"),
                MongoAccessCodeEmailOutbox.class);
    }

    private void markStatus(MongoAccessCodeEmailOutbox job, EmailOutboxStatus status,
                            String providerMessageId, String lastError) {
        LocalDateTime now = LocalDateTime.now();
        Update update = new Update().set("status", status.name()).set("updatedAt", now);
        if (providerMessageId != null) {
            update.set("providerMessageId", providerMessageId).set("sentAt", now);
        }
        if (lastError != null) {
            update.set("lastError", lastError);
        }
        // The rendered bodies carry a live credential. Once the provider has
        // accepted the message they are dead weight, so drop them instead of
        // leaving the code readable at rest in Mongo.
        if (status == EmailOutboxStatus.SUBMITTED) {
            update.set("subject", null).set("html", null).set("text", null);
        }
        mongoTemplate.updateFirst(
                new Query(Criteria.where("id").is(job.getId())
                        .and("status").in(EmailOutboxStatus.PENDING.name(), EmailOutboxStatus.IN_FLIGHT.name())),
                update, MongoAccessCodeEmailOutbox.class);
    }

    private String buildHtml(ImportedStudentAccess s, String registerUrl) {
        String name = escapeHtml(trimTo(s.name(), 80));
        String regNo = escapeHtml(trimTo(s.registerNumber(), 40));
        String code = escapeHtml(trimTo(s.accessCode(), 16));
        return """
                <div style="font-family: Arial, sans-serif; max-width: 500px; margin: 0 auto; padding: 24px; border: 1px solid #e2e8f0; border-radius: 8px;">
                  <h2 style="color: #1e293b; margin-top: 0;">Placement Portal</h2>
                  <p style="color: #475569; font-size: 15px;">Hello %s,</p>
                  <p style="color: #475569; font-size: 15px;">You have been added to the placement portal roster. Use the access code below to create your account.</p>
                  <div style="background-color: #f1f5f9; padding: 16px; border-radius: 6px; text-align: center; margin: 20px 0;">
                    <span style="font-size: 28px; font-weight: bold; letter-spacing: 6px; color: #0f172a;">%s</span>
                  </div>
                  <p style="color: #64748b; font-size: 13px;">Register number: %s</p>
                  <p style="margin-top: 24px;"><a href="%s" style="color: #2563eb;">Activate your account</a></p>
                  <hr style="border: none; border-top: 1px solid #e2e8f0; margin: 20px 0;" />
                  <p style="color: #94a3b8; font-size: 12px; margin-bottom: 0;">This code is personal to you. Do not share it. If you were not expecting this email, you can safely ignore it.</p>
                </div>
                """.formatted(name, code, regNo, escapeHtmlAttr(registerUrl));
    }

    private String buildText(ImportedStudentAccess s, String registerUrl) {
        return """
                Placement Portal

                Hello %s,

                You have been added to the placement portal roster. Use the access code below to create your account.

                %s

                Register number: %s

                Activate your account:
                %s

                This code is personal to you. Do not share it. If you were not expecting this email, you can safely ignore it.
                """.formatted(trimTo(s.name(), 80), trimTo(s.accessCode(), 16),
                trimTo(s.registerNumber(), 40), registerUrl);
    }

    /** Applies a verified provider delivery event to an access-code row. */
    public boolean applyDeliveryEvent(String providerMessageId, EmailOutboxStatus status, String eventName) {
        if (providerMessageId == null || providerMessageId.isBlank() || status == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        Update update = new Update().set("status", status.name())
                .set("lastEventName", eventName)
                .set("lastEventAt", now)
                .set("updatedAt", now);
        if (status == EmailOutboxStatus.DELIVERED) {
            update.set("deliveredAt", now);
        }
        if (status == EmailOutboxStatus.BOUNCED || status == EmailOutboxStatus.COMPLAINED) {
            update.set("bouncedAt", now);
        }
        if (status == EmailOutboxStatus.SUPPRESSED) {
            update.set("suppressedAt", now);
        }
        return mongoTemplate.updateFirst(
                new Query(Criteria.where("providerMessageId").is(providerMessageId)
                        .and("status").nin(EmailOutboxStatus.DELIVERED.name(),
                                EmailOutboxStatus.BOUNCED.name(),
                                EmailOutboxStatus.COMPLAINED.name(),
                                EmailOutboxStatus.SUPPRESSED.name())),
                update, MongoAccessCodeEmailOutbox.class).getModifiedCount() > 0;
    }

    private boolean isValidEmail(String email) {
        return email != null && !email.isBlank()
                && !email.toLowerCase().endsWith("@example.com")
                && !CONTROL_CHARS.matcher(email).find()
                && EMAIL_PATTERN.matcher(email).matches();
    }

    private String trimTo(String value, int max) {
        if (value == null) {
            return "";
        }
        String cleaned = CONTROL_CHARS.matcher(value).replaceAll(" ").trim();
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    private String sanitize(String value) {
        return trimTo(value, 300);
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String escapeHtmlAttr(String value) {
        return escapeHtml(value);
    }
}
