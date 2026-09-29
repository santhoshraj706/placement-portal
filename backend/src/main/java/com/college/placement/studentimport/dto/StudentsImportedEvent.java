package com.college.placement.studentimport.dto;

import java.util.List;

/**
 * Published by {@code StudentImportService.confirm()} once every roster row has
 * been persisted. The listener reacts AFTER_COMMIT so an email can never be sent
 * for a student whose authorization was rolled back.
 *
 * <p>Carries plaintext access codes, so this record must never be logged.
 */
public record StudentsImportedEvent(String batchId, List<ImportedStudentAccess> students) {
}
