package com.college.placement.messaging.mongo.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * Outbox for student access-code emails issued by the PO roster import.
 *
 * <p>One row per (accessCodeId, eventKey) so a re-published import event can
 * never email the same authorization twice.
 *
 * <p>The rendered {@code subject}/{@code html}/{@code text} embed the plaintext
 * access code, which is why {@code AccessCodeEmailNotificationService} scrubs
 * them once the provider has accepted the message.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "access_code_email_outbox")
@CompoundIndexes({
        @CompoundIndex(name = "idx_access_outbox_dedupe_unique",
                def = "{'accessCodeId': 1, 'eventKey': 1}", unique = true),
        @CompoundIndex(name = "idx_access_outbox_status_next",
                def = "{'status': 1, 'nextAttemptAt': 1}"),
        @CompoundIndex(name = "idx_access_outbox_provider", def = "{'providerMessageId': 1}")
})
public class MongoAccessCodeEmailOutbox {

    @Id
    private String id;

    /** student_access_codes.id this notification belongs to. */
    private Long accessCodeId;

    /** Stable event identity, e.g. ACCESS_CODE_ISSUED. Part of the dedupe key. */
    private String eventKey;

    private String batchId;

    private String recipientEmail;

    private String registerNumber;

    /**
     * Rendered content captured at enqueue time so the worker never needs to
     * read roster tables and pending jobs survive a restart unchanged.
     */
    private String subject;

    private String html;

    private String text;

    private String status;

    private Integer attempts;

    private LocalDateTime nextAttemptAt;

    private LocalDateTime startedAt;

    private String providerMessageId;

    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime sentAt;

    private LocalDateTime deliveredAt;

    private LocalDateTime bouncedAt;

    private String lastEventName;

    private LocalDateTime lastEventAt;
}
