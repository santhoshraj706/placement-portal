package com.college.placement.studentimport.dto;

/**
 * One successfully imported roster row, carried in memory only long enough to
 * render the access-code email. The plaintext {@code accessCode} is never
 * persisted here and never logged.
 */
public record ImportedStudentAccess(
        Long accessCodeId,
        String email,
        String name,
        String registerNumber,
        String accessCode) {
}
