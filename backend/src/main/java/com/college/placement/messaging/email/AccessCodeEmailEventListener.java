package com.college.placement.messaging.email;

import com.college.placement.studentimport.dto.StudentsImportedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Queues the access-code email fanout after a roster import commits.
 *
 * <p>Runs AFTER_COMMIT so a rolled-back import can never email a code that was
 * never persisted. Any failure here is logged and swallowed on purpose: the
 * roster rows are already durable and must not be reversed by an email problem.
 * The PO still receives every plaintext code in the import response, so the
 * authorization remains distributable by hand.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AccessCodeEmailEventListener {

    private final AccessCodeEmailNotificationService accessCodeEmailNotificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStudentsImported(StudentsImportedEvent event) {
        try {
            accessCodeEmailNotificationService.enqueueAccessCodeIssued(
                    event.batchId(), event.students());
        } catch (RuntimeException e) {
            log.error("[EMAIL] Failed to queue access-code notifications for batch {}: {}",
                    event.batchId(), String.valueOf(e.getMessage()).replaceAll("[\\r\\n]", " "));
        }
    }
}
