package com.college.placement.auth;

import com.college.placement.accesscode.AccessCodeHasher;
import com.college.placement.audit.AuditService;
import com.college.placement.auth.dto.RegisterRequest;
import com.college.placement.auth.dto.RegisterResponse;
import com.college.placement.common.enums.Role;
import com.college.placement.common.exception.BadRequestException;
import com.college.placement.common.exception.ConflictException;
import com.college.placement.department.Department;
import com.college.placement.department.DepartmentCodeResolver;
import com.college.placement.security.SecurityUtils;
import com.college.placement.student.StudentAccessCode;
import com.college.placement.student.StudentAccessCodeRepository;
import com.college.placement.student.StudentProfile;
import com.college.placement.student.StudentProfileRepository;
import com.college.placement.user.User;
import com.college.placement.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private StudentProfileRepository studentProfileRepository;
    @Mock
    private StudentAccessCodeRepository accessCodeRepository;
    @Mock
    private DepartmentCodeResolver departmentResolver;
    @Mock
    private AccessCodeHasher accessCodeHasher;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private AuthService authService;

    private StudentAccessCode mockRosterRecord;
    private Department mockDepartment;

    @BeforeEach
    void setUp() {
        mockDepartment = Department.builder()
                .id(1L)
                .name("Computer Science and Engineering")
                .active(true)
                .build();

        mockRosterRecord = StudentAccessCode.builder()
                .id(100L)
                .name("Karthikeyan RJ")
                .email("karthikeyanrj@student.tce.edu")
                .registerNumber("24C21031")
                .departmentCode("CSE")
                .active(true)
                .codeHash("hash123")
                .build();
    }

    @Test
    void testRegisterStudent_NonTceDomainRejected() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("karthikeyanrj@gmail.com");
        request.setRegisterNumber("24C21031");
        request.setAccessCode("abc12345");
        request.setPassword("password");

        assertThatThrownBy(() -> authService.registerStudent(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("This email is not authorized for registration.");
    }

    @Test
    void testRegisterStudent_UsedCodeRejected() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("karthikeyanrj@student.tce.edu");
        request.setRegisterNumber("24C21031");
        request.setAccessCode("abc12345");
        request.setPassword("password");

        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(studentProfileRepository.existsByRegisterNumber(any())).thenReturn(false);
        when(accessCodeRepository.findByRegisterNumberEndingWith("21031")).thenReturn(List.of(mockRosterRecord));
        when(accessCodeHasher.matches("abc12345", "hash123")).thenReturn(true);

        mockRosterRecord.setUsedAt(LocalDateTime.now());

        assertThatThrownBy(() -> authService.registerStudent(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("This access code has already been used.");
    }

    @Test
    void testRegisterStudent_WrongCodeRejected() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("karthikeyanrj@student.tce.edu");
        request.setRegisterNumber("24C21031");
        request.setAccessCode("wrongcode");
        request.setPassword("password");

        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(studentProfileRepository.existsByRegisterNumber(any())).thenReturn(false);
        when(accessCodeRepository.findByRegisterNumberEndingWith("21031")).thenReturn(List.of(mockRosterRecord));
        when(accessCodeHasher.matches("wrongcode", "hash123")).thenReturn(false);

        assertThatThrownBy(() -> authService.registerStudent(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid access code for this student.");
    }

    @Test
    void testRegisterStudent_HappyPath() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("karthikeyanrj@student.tce.edu");
        request.setRegisterNumber("24C21031");
        request.setAccessCode("abc12345");
        request.setPassword("password");

        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(studentProfileRepository.existsByRegisterNumber(any())).thenReturn(false);
        when(accessCodeRepository.findByRegisterNumberEndingWith("21031")).thenReturn(List.of(mockRosterRecord));
        when(accessCodeHasher.matches("abc12345", "hash123")).thenReturn(true);
        when(departmentResolver.resolve("CSE")).thenReturn(Optional.of(mockDepartment));
        when(passwordEncoder.encode("password")).thenReturn("encoded_password");

        User savedUser = User.builder()
                .id(1L)
                .name(mockRosterRecord.getName())
                .email(request.getEmail())
                .role(Role.STUDENT)
                .build();
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(studentProfileRepository.save(any(StudentProfile.class))).thenReturn(new StudentProfile());

        RegisterResponse response = authService.registerStudent(request);

        assertThat(response.getUserId()).isEqualTo(1L);
        assertThat(response.getEmail()).isEqualTo(request.getEmail());
        assertThat(mockRosterRecord.getUsedAt()).isNotNull();
        assertThat(mockRosterRecord.getActive()).isFalse();

        verify(accessCodeRepository).save(mockRosterRecord);
    }
}
