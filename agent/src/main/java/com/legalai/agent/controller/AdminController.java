package com.legalai.agent.controller;

import com.legalai.agent.entity.AuditLog;
import com.legalai.agent.entity.ComplianceRule;
import com.legalai.agent.repository.AuditLogRepository;
import com.legalai.agent.repository.ComplianceRuleRepository;
import com.legalai.agent.service.ActivityMonitorService;
import com.legalai.agent.service.ComplianceRuleLoaderService;
import com.legalai.agent.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin Controller for system administration and monitoring
 * Restricted to ADMIN role only
 * Provides endpoints for audit trails, user management, session management, and system statistics
 */
@RestController
@RequestMapping("/admin")
@CrossOrigin(origins = "*", maxAge = 3600)
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    @Autowired
    private ActivityMonitorService activityMonitorService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private ComplianceRuleRepository complianceRuleRepository;

    @Autowired
    private ComplianceRuleLoaderService complianceRuleLoaderService;

    @Autowired
    private InMemoryUserDetailsManager userDetailsManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * Get audit trail for a specific document
     * Retrieves paginated immutable audit logs
     * 
     * @param docId Document ID
     * @param page Page number (default: 0)
     * @param size Page size (default: 20)
     * @return Paginated audit trail
     */
    @GetMapping("/audit/{docId}")
    public ResponseEntity<?> getAuditTrail(
            @PathVariable Long docId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        
        logger.info("Admin audit trail request: docId={}, page={}, size={}", docId, page, size);
        
        try {
            ActivityMonitorService.AuditTrailResult auditTrail = 
                    activityMonitorService.getAuditTrailForDoc(docId, page, size);
            
            auditLogger.info("ADMIN_AUDIT_ACCESS: DocumentId={}, Page={}, Size={}", docId, page, size);
            
            return ResponseEntity.ok(auditTrail);
            
        } catch (Exception e) {
            logger.error("Failed to retrieve audit trail: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Audit trail retrieval failed: " + e.getMessage()));
        }
    }

    /**
     * Get all audit logs with filtering
     * 
     * @param user Filter by username
     * @param action Filter by action
     * @param startDate Filter by start date
     * @param endDate Filter by end date
     * @return List of audit logs
     */
    @GetMapping("/audit/all")
    public ResponseEntity<?> getAllAuditLogs(
            @RequestParam(value = "user", required = false) String user,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate) {
        
        logger.info("Admin all audit logs request: user={}, action={}", user, action);
        
        try {
            List<AuditLog> logs;
            
            if (user != null && startDate != null && endDate != null) {
                LocalDateTime start = LocalDateTime.parse(startDate);
                LocalDateTime end = LocalDateTime.parse(endDate);
                logs = auditLogRepository.findByUserAndTimestampBetween(user, start, end);
            } else if (user != null) {
                logs = auditLogRepository.findByUser(user);
            } else if (action != null) {
                logs = auditLogRepository.findByAction(action);
            } else {
                logs = auditLogRepository.findTop100ByOrderByTimestampDesc();
            }
            
            auditLogger.info("ADMIN_AUDIT_ALL_ACCESS: Filters=[user={}, action={}], ResultCount={}", 
                    user, action, logs.size());
            
            return ResponseEntity.ok(logs);
            
        } catch (Exception e) {
            logger.error("Failed to retrieve audit logs: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Audit logs retrieval failed: " + e.getMessage()));
        }
    }

    /**
     * Create a new user with RBAC management
     * 
     * @param request User creation request with username, password, and roles
     * @return Success response
     */
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody UserCreationRequest request) {
        logger.info("Admin create user request: username={}, roles={}", 
                request.getUsername(), request.getRoles());
        
        try {
            // Validate request
            if (request.getUsername() == null || request.getUsername().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Username is required"));
            }
            
            if (request.getPassword() == null || request.getPassword().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Password is required"));
            }
            
            if (request.getRoles() == null || request.getRoles().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "At least one role is required"));
            }
            
            // Check if user already exists
            if (userDetailsManager.userExists(request.getUsername())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "User already exists"));
            }
            
            // Create user with roles
            UserDetails newUser = User.builder()
                    .username(request.getUsername())
                    .password(passwordEncoder.encode(request.getPassword()))
                    .roles(request.getRoles().toArray(new String[0]))
                    .build();
            
            userDetailsManager.createUser(newUser);
            
            auditLogger.warn("ADMIN_USER_CREATED: Username={}, Roles={}", 
                    request.getUsername(), request.getRoles());
            
            logger.info("User created successfully: {}", request.getUsername());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "User created successfully",
                "username", request.getUsername(),
                "roles", request.getRoles()
            ));
            
        } catch (Exception e) {
            logger.error("Failed to create user: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "User creation failed: " + e.getMessage()));
        }
    }

    /**
     * Update user roles
     * 
     * @param username Username to update
     * @param request Update request with new roles
     * @return Success response
     */
    @PutMapping("/users/{username}/roles")
    public ResponseEntity<?> updateUserRoles(
            @PathVariable String username,
            @RequestBody UserRoleUpdateRequest request) {
        
        logger.info("Admin update user roles request: username={}, roles={}", 
                username, request.getRoles());
        
        try {
            if (!userDetailsManager.userExists(username)) {
                return ResponseEntity.notFound().build();
            }
            
            // Get existing user
            UserDetails existingUser = userDetailsManager.loadUserByUsername(username);
            
            // Create updated user with new roles
            UserDetails updatedUser = User.builder()
                    .username(username)
                    .password(existingUser.getPassword())
                    .roles(request.getRoles().toArray(new String[0]))
                    .build();
            
            userDetailsManager.updateUser(updatedUser);
            
            auditLogger.warn("ADMIN_USER_ROLES_UPDATED: Username={}, NewRoles={}", 
                    username, request.getRoles());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "User roles updated successfully",
                "username", username,
                "roles", request.getRoles()
            ));
            
        } catch (Exception e) {
            logger.error("Failed to update user roles: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Role update failed: " + e.getMessage()));
        }
    }

    /**
     * Delete a user
     * 
     * @param username Username to delete
     * @return Success response
     */
    @DeleteMapping("/users/{username}")
    public ResponseEntity<?> deleteUser(@PathVariable String username) {
        logger.info("Admin delete user request: username={}", username);
        
        try {
            if (!userDetailsManager.userExists(username)) {
                return ResponseEntity.notFound().build();
            }
            
            userDetailsManager.deleteUser(username);
            
            auditLogger.warn("ADMIN_USER_DELETED: Username={}", username);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "User deleted successfully",
                "username", username
            ));
            
        } catch (Exception e) {
            logger.error("Failed to delete user: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "User deletion failed: " + e.getMessage()));
        }
    }

    /**
     * List all users (in-memory user details)
     * Note: This is a simplified version for InMemoryUserDetailsManager
     * 
     * @return List of users (limited information for security)
     */
    @GetMapping("/users")
    public ResponseEntity<?> listUsers() {
        logger.info("Admin list users request");
        
        try {
            // For InMemoryUserDetailsManager, we can only list hardcoded users
            // In production with database-backed user management, this would be comprehensive
            List<Map<String, Object>> users = Arrays.asList(
                Map.of("username", "lawyer", "roles", List.of("LAWYER")),
                Map.of("username", "admin", "roles", List.of("ADMIN")),
                Map.of("username", "clerk", "roles", List.of("CLERK"))
            );
            
            auditLogger.info("ADMIN_USERS_LIST_ACCESS");
            
            return ResponseEntity.ok(users);
            
        } catch (Exception e) {
            logger.error("Failed to list users: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "User listing failed: " + e.getMessage()));
        }
    }

    /**
     * Get active sessions
     * 
     * @return List of active sessions
     */
    @GetMapping("/sessions")
    public ResponseEntity<?> getActiveSessions() {
        logger.info("Admin get active sessions request");
        
        try {
            List<String> activeUsers = sessionService.getActiveUsers();
            int totalSessions = sessionService.getTotalActiveSessions();
            String statistics = sessionService.getSessionStatistics();
            
            Map<String, Object> response = new HashMap<>();
            response.put("activeUsers", activeUsers);
            response.put("totalSessions", totalSessions);
            response.put("statistics", statistics);
            
            auditLogger.info("ADMIN_SESSIONS_ACCESS: ActiveUsers={}, TotalSessions={}", 
                    activeUsers.size(), totalSessions);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Failed to retrieve sessions: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Session retrieval failed: " + e.getMessage()));
        }
    }

    /**
     * Expire all sessions for a user
     * 
     * @param username Username whose sessions to expire
     * @return Success response
     */
    @PostMapping("/sessions/expire/{username}")
    public ResponseEntity<?> expireUserSessions(@PathVariable String username) {
        logger.info("Admin expire sessions request: username={}", username);
        
        try {
            int expiredCount = sessionService.expireAllSessionsForUser(username);
            
            auditLogger.warn("ADMIN_SESSIONS_EXPIRED: Username={}, Count={}", username, expiredCount);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Sessions expired successfully",
                "username", username,
                "expiredCount", expiredCount
            ));
            
        } catch (Exception e) {
            logger.error("Failed to expire sessions: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Session expiration failed: " + e.getMessage()));
        }
    }

    /**
     * Get system statistics
     * 
     * @return System statistics including audit logs, sessions, users
     */
    @GetMapping("/statistics")
    public ResponseEntity<?> getSystemStatistics() {
        logger.info("Admin get system statistics request");
        
        try {
            // Get audit log statistics
            List<AuditLog> recentLogs = auditLogRepository.findTop100ByOrderByTimestampDesc();
            
            Map<String, Long> auditsByAction = recentLogs.stream()
                    .collect(Collectors.groupingBy(AuditLog::getAction, Collectors.counting()));
            
            Map<String, Long> auditsByUser = recentLogs.stream()
                    .collect(Collectors.groupingBy(AuditLog::getUser, Collectors.counting()));
            
            long successCount = recentLogs.stream()
                    .filter(log -> "SUCCESS".equals(log.getOutcome()))
                    .count();
            
            long failureCount = recentLogs.stream()
                    .filter(log -> "FAILURE".equals(log.getOutcome()))
                    .count();
            
            // Get session statistics
            int totalSessions = sessionService.getTotalActiveSessions();
            List<String> activeUsers = sessionService.getActiveUsers();
            
            // Build response
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("auditLogs", Map.of(
                "totalRecent", recentLogs.size(),
                "successCount", successCount,
                "failureCount", failureCount,
                "byAction", auditsByAction,
                "byUser", auditsByUser
            ));
            statistics.put("sessions", Map.of(
                "totalActive", totalSessions,
                "activeUsers", activeUsers.size(),
                "userList", activeUsers
            ));
            statistics.put("timestamp", LocalDateTime.now());
            
            auditLogger.info("ADMIN_STATISTICS_ACCESS");
            
            return ResponseEntity.ok(statistics);
            
        } catch (Exception e) {
            logger.error("Failed to retrieve statistics: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Statistics retrieval failed: " + e.getMessage()));
        }
    }

    /**
     * Get all compliance rules
     * 
     * @return List of compliance rules
     */
    @GetMapping("/compliance/rules")
    public ResponseEntity<?> getComplianceRules() {
        logger.info("Admin get compliance rules request");
        
        try {
            List<ComplianceRule> rules = (List<ComplianceRule>) complianceRuleRepository.findAll();
            
            auditLogger.info("ADMIN_COMPLIANCE_RULES_ACCESS: RuleCount={}", rules.size());
            
            return ResponseEntity.ok(rules);
            
        } catch (Exception e) {
            logger.error("Failed to retrieve compliance rules: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Compliance rules retrieval failed: " + e.getMessage()));
        }
    }

    /**
     * Create a new compliance rule
     * 
     * @param rule Compliance rule to create
     * @return Created rule
     */
    @PostMapping("/compliance/rules")
    public ResponseEntity<?> createComplianceRule(@RequestBody ComplianceRule rule) {
        logger.info("Admin create compliance rule request: ruleName={}, jurisdiction={}", 
                rule.getRuleName(), rule.getJurisdiction());
        
        try {
            ComplianceRule savedRule = complianceRuleRepository.save(rule);
            
            auditLogger.warn("ADMIN_COMPLIANCE_RULE_CREATED: RuleId={}, RuleName={}, Jurisdiction={}", 
                    savedRule.getId(), savedRule.getRuleName(), savedRule.getJurisdiction());
            
            return ResponseEntity.ok(savedRule);
            
        } catch (Exception e) {
            logger.error("Failed to create compliance rule: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Rule creation failed: " + e.getMessage()));
        }
    }

    /**
     * Update a compliance rule
     * 
     * @param id Rule ID
     * @param rule Updated rule
     * @return Updated rule
     */
    @PutMapping("/compliance/rules/{id}")
    public ResponseEntity<?> updateComplianceRule(@PathVariable Long id, @RequestBody ComplianceRule rule) {
        logger.info("Admin update compliance rule request: id={}", id);
        
        try {
            Optional<ComplianceRule> existingRule = complianceRuleRepository.findById(id);
            
            if (existingRule.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            rule.setId(id);
            ComplianceRule updatedRule = complianceRuleRepository.save(rule);
            
            auditLogger.warn("ADMIN_COMPLIANCE_RULE_UPDATED: RuleId={}, RuleName={}", 
                    updatedRule.getId(), updatedRule.getRuleName());
            
            return ResponseEntity.ok(updatedRule);
            
        } catch (Exception e) {
            logger.error("Failed to update compliance rule: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Rule update failed: " + e.getMessage()));
        }
    }

    /**
     * Delete a compliance rule
     * 
     * @param id Rule ID
     * @return Success response
     */
    @DeleteMapping("/compliance/rules/{id}")
    public ResponseEntity<?> deleteComplianceRule(@PathVariable Long id) {
        logger.info("Admin delete compliance rule request: id={}", id);
        
        try {
            Optional<ComplianceRule> existingRule = complianceRuleRepository.findById(id);
            
            if (existingRule.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            complianceRuleRepository.deleteById(id);
            
            auditLogger.warn("ADMIN_COMPLIANCE_RULE_DELETED: RuleId={}", id);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Compliance rule deleted successfully",
                "ruleId", id
            ));
            
        } catch (Exception e) {
            logger.error("Failed to delete compliance rule: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Rule deletion failed: " + e.getMessage()));
        }
    }

    /**
     * Reload compliance rules from JSON file
     * 
     * @return Number of rules loaded
     */
    @PostMapping("/compliance/rules/reload")
    public ResponseEntity<?> reloadComplianceRules() {
        logger.info("Admin reload compliance rules request");
        
        try {
            int loadedCount = complianceRuleLoaderService.reloadRules();
            
            auditLogger.warn("ADMIN_RULES_RELOADED: Count={}", loadedCount);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Compliance rules reloaded successfully",
                "rulesLoaded", loadedCount
            ));
            
        } catch (Exception e) {
            logger.error("Failed to reload compliance rules: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to reload rules: " + e.getMessage()));
        }
    }

    /**
     * Get compliance rule statistics
     * 
     * @return Rule statistics by jurisdiction and severity
     */
    @GetMapping("/compliance/rules/statistics")
    public ResponseEntity<?> getComplianceRuleStatistics() {
        logger.info("Admin compliance rule statistics request");
        
        try {
            com.legalai.agent.service.ComplianceRuleLoaderService.RuleStatistics stats = 
                    complianceRuleLoaderService.getRuleStatistics();
            
            auditLogger.info("ADMIN_COMPLIANCE_STATS_ACCESS: TotalRules={}", stats.getTotalRules());
            
            return ResponseEntity.ok(stats);
            
        } catch (Exception e) {
            logger.error("Failed to get compliance statistics: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get statistics: " + e.getMessage()));
        }
    }

    /**
     * Load compliance rules from JSON string
     * Useful for importing custom rule sets
     * 
     * @param request JSON content with rules
     * @return Number of rules loaded
     */
    @PostMapping("/compliance/rules/import")
    public ResponseEntity<?> importComplianceRules(@RequestBody Map<String, String> request) {
        logger.info("Admin import compliance rules request");
        
        try {
            String jsonContent = request.get("jsonContent");
            
            if (jsonContent == null || jsonContent.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "JSON content is required"));
            }
            
            int loadedCount = complianceRuleLoaderService.loadRulesFromString(jsonContent);
            
            auditLogger.warn("ADMIN_RULES_IMPORTED: Count={}", loadedCount);
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Compliance rules imported successfully",
                "rulesLoaded", loadedCount
            ));
            
        } catch (Exception e) {
            logger.error("Failed to import compliance rules: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to import rules: " + e.getMessage()));
        }
    }

    /**
     * Get rules by jurisdiction
     * 
     * @param jurisdiction Jurisdiction code
     * @return List of rules for jurisdiction
     */
    @GetMapping("/compliance/rules/jurisdiction/{jurisdiction}")
    public ResponseEntity<?> getRulesByJurisdiction(@PathVariable String jurisdiction) {
        logger.info("Admin get rules by jurisdiction request: {}", jurisdiction);
        
        try {
            List<ComplianceRule> rules = complianceRuleRepository
                    .findByJurisdictionAndActive(jurisdiction, true);
            
            auditLogger.info("ADMIN_RULES_BY_JURISDICTION: Jurisdiction={}, Count={}", 
                    jurisdiction, rules.size());
            
            return ResponseEntity.ok(rules);
            
        } catch (Exception e) {
            logger.error("Failed to get rules by jurisdiction: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get rules: " + e.getMessage()));
        }
    }

    /**
     * Toggle rule active status
     * 
     * @param id Rule ID
     * @return Updated rule
     */
    @PatchMapping("/compliance/rules/{id}/toggle")
    public ResponseEntity<?> toggleRuleStatus(@PathVariable Long id) {
        logger.info("Admin toggle rule status request: id={}", id);
        
        try {
            Optional<ComplianceRule> ruleOpt = complianceRuleRepository.findById(id);
            
            if (ruleOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            ComplianceRule rule = ruleOpt.get();
            rule.setActive(!rule.getActive());
            ComplianceRule updatedRule = complianceRuleRepository.save(rule);
            
            auditLogger.warn("ADMIN_RULE_TOGGLED: RuleId={}, RuleName={}, Active={}", 
                    id, rule.getRuleName(), rule.getActive());
            
            return ResponseEntity.ok(updatedRule);
            
        } catch (Exception e) {
            logger.error("Failed to toggle rule status: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to toggle rule: " + e.getMessage()));
        }
    }

    /**
     * Health check for admin endpoints
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "Legal AI Admin Service",
            "timestamp", System.currentTimeMillis()
        ));
    }

    // Request DTOs

    public static class UserCreationRequest {
        private String username;
        private String password;
        private List<String> roles;

        // Getters and Setters
        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }

    public static class UserRoleUpdateRequest {
        private List<String> roles;

        // Getters and Setters
        public List<String> getRoles() {
            return roles;
        }

        public void setRoles(List<String> roles) {
            this.roles = roles;
        }
    }
}

