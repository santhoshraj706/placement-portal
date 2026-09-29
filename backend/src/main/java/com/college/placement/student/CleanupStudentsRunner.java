package com.college.placement.student;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
@Order(1) // Run before other runners
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.cleanup-students.enabled", havingValue = "true", matchIfMissing = false)
public class CleanupStudentsRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== CleanupStudentsRunner STARTING ===");

        List<String> targetEmails = List.of(
                "karthikeyanrj@student.tce.edu",
                "santhoshraj@student.tce.edu",
                "2403917714821001@tce.edu",
                "2403917714821002@tce.edu",
                "2403917714821003@tce.edu",
                "abubakkar.f2@student.tce.edu",
                "ashna@student.tce.edu"
        );

        List<String> targetRegNumbers = List.of(
                "22C21031",
                "22cs1084",
                "2403917714821001",
                "2403917714821002",
                "2403917714821003",
                "2403917714821004",
                "24C22116"
        );

        // 1. Find all user IDs to delete (by email)
        List<Long> userIds = new ArrayList<>();
        for (String email : targetEmails) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM users WHERE LOWER(email) = LOWER(?)", Long.class, email);
            userIds.addAll(ids);
            if (!ids.isEmpty()) log.info("Found user(s) for email {}: {}", email, ids);
        }

        // 2. Find all student_profile IDs to delete (by register number + by user_id)
        List<Long> profileIds = new ArrayList<>();
        for (String regNum : targetRegNumbers) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM student_profiles WHERE register_number = ?", Long.class, regNum);
            profileIds.addAll(ids);
            if (!ids.isEmpty()) log.info("Found profile(s) for regNum {}: {}", regNum, ids);
        }
        for (Long uId : userIds) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM student_profiles WHERE user_id = ?", Long.class, uId);
            for (Long id : ids) {
                if (!profileIds.contains(id)) profileIds.add(id);
            }
        }

        log.info("Total profiles to delete: {}, Total users to delete: {}", profileIds.size(), userIds.size());

        // 3. Unlink and delete student_access_codes
        for (String regNum : targetRegNumbers) {
            int n = jdbcTemplate.update("DELETE FROM student_access_codes WHERE register_number = ?", regNum);
            if (n > 0) log.info("Deleted {} access_code(s) for regNum {}", n, regNum);
        }
        for (String email : targetEmails) {
            int n = jdbcTemplate.update("DELETE FROM student_access_codes WHERE LOWER(email) = LOWER(?)", email);
            if (n > 0) log.info("Deleted {} access_code(s) for email {}", n, email);
        }

        // 4. Delete dependent rows from child tables for each profile
        for (Long pId : profileIds) {
            safeDelete("DELETE FROM student_interviews WHERE student_profile_id = ?", pId);
            safeDelete("DELETE FROM student_placement_info WHERE student_profile_id = ?", pId);
            safeDelete("DELETE FROM student_academics WHERE student_profile_id = ?", pId);
            safeDelete("DELETE FROM student_professionals WHERE student_profile_id = ?", pId);
            // Unlink access codes referencing this profile
            safeDelete("UPDATE student_access_codes SET student_profile_id = NULL WHERE student_profile_id = ?", pId);
            safeDelete("DELETE FROM student_profiles WHERE id = ?", pId);
            log.info("Deleted student_profile id={}", pId);
        }

        // 5. Delete message_recipients and other user-linked rows, then delete users
        for (Long uId : userIds) {
            safeDelete("DELETE FROM message_recipients WHERE recipient_id = ?", uId);
            safeDelete("DELETE FROM message_reactions WHERE user_id = ?", uId);
            safeDelete("DELETE FROM forum_posts WHERE user_id = ?", uId);
            // Delete any remaining student_profiles for this user (safety)
            safeDelete("DELETE FROM student_profiles WHERE user_id = ?", uId);
            safeDelete("DELETE FROM users WHERE id = ?", uId);
            log.info("Deleted user id={}", uId);
        }

        log.info("=== CleanupStudentsRunner FINISHED ===");
    }

    private void safeDelete(String sql, Object... args) {
        try {
            jdbcTemplate.update(sql, args);
        } catch (Exception e) {
            log.warn("SafeDelete failed for SQL '{}': {}", sql, e.getMessage());
        }
    }
}
