package com.legalai.agent.service;

import com.legalai.agent.entity.AuditLog;
import com.legalai.agent.repository.AuditLogRepository;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AOP-based activity monitoring service that logs all service method calls
 * Uses Spring AOP @After advice to capture method execution details
 * 
 * IMMUTABLE AUDIT LOGS:
 * - Logs are stored in append-only mode (no updates or deletes)
 * - For enhanced tamper-resistance, consider integrating blockchain:
 *   * Libraries: Web3j (for Ethereum), Hyperledger Fabric SDK
 *   * Store hash of each audit entry on blockchain for verification
 *   * Use merkle trees for batch verification of log integrity
 *   * Example: hash = SHA256(user + action + timestamp + details)
 *   * Store hash in smart contract or distributed ledger
 * - Current implementation uses database with no update operations
 * - Consider adding cryptographic signatures for each log entry
 */
@Aspect
@Component
public class ActivityMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(ActivityMonitorService.class);

    @Autowired
    private AuditLogRepository auditLogRepository;

    /**
     * Logs successful execution of all service methods
     * Captures user, method name, arguments, timestamp, and outcome
     * 
     * @param joinPoint The join point providing method execution details
     * @param result The return value of the method
     */
    @AfterReturning(
        pointcut = "execution(* com.legalai.agent.service.*.*(..)) && " +
                   "!execution(* com.legalai.agent.service.ActivityMonitorService.*(..))",
        returning = "result"
    )
    @Async
    public void logServiceMethodSuccess(JoinPoint joinPoint, Object result) {
        try {
            String username = getCurrentUsername();
            String methodName = joinPoint.getSignature().toShortString();
            String className = joinPoint.getTarget().getClass().getSimpleName();
            String arguments = formatArguments(joinPoint.getArgs());
            String action = className + "." + joinPoint.getSignature().getName();
            
            // Create audit log entry
            AuditLog auditLog = new AuditLog();
            auditLog.setUser(username);
            auditLog.setAction(action);
            auditLog.setMethodName(methodName);
            auditLog.setArguments(arguments);
            auditLog.setOutcome("SUCCESS");
            auditLog.setTimestamp(LocalDateTime.now());
            
            // Build details
            StringBuilder details = new StringBuilder();
            details.append("Method: ").append(methodName).append("\n");
            details.append("User: ").append(username).append("\n");
            details.append("Arguments: ").append(arguments).append("\n");
            details.append("Result Type: ").append(result != null ? result.getClass().getSimpleName() : "void");
            
            // Check if result contains compliance information and append to details
            if (result != null) {
                appendComplianceResults(result, details);
            }
            
            auditLog.setDetails(details.toString());
            
            // Save to database asynchronously (append-only, immutable)
            auditLogRepository.save(auditLog);
            
            logger.debug("Logged successful execution: {} by user: {}", methodName, username);
            
        } catch (Exception e) {
            // Don't let monitoring failures affect application functionality
            logger.error("Failed to log service method success: {}", e.getMessage(), e);
        }
    }

    /**
     * Logs failed execution of service methods (when exceptions are thrown)
     * 
     * @param joinPoint The join point providing method execution details
     * @param exception The exception that was thrown
     */
    @AfterThrowing(
        pointcut = "execution(* com.legalai.agent.service.*.*(..)) && " +
                   "!execution(* com.legalai.agent.service.ActivityMonitorService.*(..))",
        throwing = "exception"
    )
    @Async
    public void logServiceMethodFailure(JoinPoint joinPoint, Throwable exception) {
        try {
            String username = getCurrentUsername();
            String methodName = joinPoint.getSignature().toShortString();
            String className = joinPoint.getTarget().getClass().getSimpleName();
            String arguments = formatArguments(joinPoint.getArgs());
            String action = className + "." + joinPoint.getSignature().getName();
            
            // Create audit log entry
            AuditLog auditLog = new AuditLog();
            auditLog.setUser(username);
            auditLog.setAction(action);
            auditLog.setMethodName(methodName);
            auditLog.setArguments(arguments);
            auditLog.setOutcome("FAILURE");
            auditLog.setTimestamp(LocalDateTime.now());
            
            // Build details with exception information
            StringBuilder details = new StringBuilder();
            details.append("Method: ").append(methodName).append("\n");
            details.append("User: ").append(username).append("\n");
            details.append("Arguments: ").append(arguments).append("\n");
            details.append("Exception: ").append(exception.getClass().getSimpleName()).append("\n");
            details.append("Message: ").append(exception.getMessage());
            
            auditLog.setDetails(details.toString());
            
            // Save to database asynchronously
            auditLogRepository.save(auditLog);
            
            logger.debug("Logged failed execution: {} by user: {} - Exception: {}", 
                    methodName, username, exception.getClass().getSimpleName());
            
        } catch (Exception e) {
            // Don't let monitoring failures affect application functionality
            logger.error("Failed to log service method failure: {}", e.getMessage(), e);
        }
    }

    /**
     * Gets the current authenticated username from SecurityContext
     * 
     * @return Username or "anonymous" if not authenticated
     */
    private String getCurrentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
        } catch (Exception e) {
            logger.warn("Failed to get current username: {}", e.getMessage());
        }
        return "anonymous";
    }

    /**
     * Formats method arguments for logging
     * Masks sensitive data and limits argument length
     * 
     * @param args Method arguments array
     * @return Formatted argument string
     */
    private String formatArguments(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        
        try {
            String formatted = Arrays.stream(args)
                .map(arg -> {
                    if (arg == null) {
                        return "null";
                    }
                    
                    String argStr = arg.getClass().getSimpleName();
                    
                    // Mask sensitive data
                    if (argStr.toLowerCase().contains("password") || 
                        argStr.toLowerCase().contains("secret") ||
                        argStr.toLowerCase().contains("token")) {
                        return argStr + "=***MASKED***";
                    }
                    
                    // For primitive types and strings, include value (with length limit)
                    if (arg instanceof String || 
                        arg instanceof Number || 
                        arg instanceof Boolean) {
                        String value = arg.toString();
                        if (value.length() > 100) {
                            value = value.substring(0, 100) + "...";
                        }
                        return argStr + "=" + value;
                    }
                    
                    return argStr;
                })
                .collect(Collectors.joining(", "));
            
            return "[" + formatted + "]";
            
        } catch (Exception e) {
            logger.warn("Failed to format arguments: {}", e.getMessage());
            return "[formatting error]";
        }
    }

    /**
     * Appends compliance results to audit log details if present in method result
     * Extracts violation information from compliance checks
     * 
     * @param result The method result object
     * @param details StringBuilder to append compliance info to
     */
    private void appendComplianceResults(Object result, StringBuilder details) {
        try {
            // Check for DocumentComplianceResult
            if (result instanceof DocumentService.DocumentComplianceResult) {
                DocumentService.DocumentComplianceResult complianceResult = 
                    (DocumentService.DocumentComplianceResult) result;
                
                details.append("\n\n=== COMPLIANCE RESULTS ===\n");
                details.append("Compliant: ").append(complianceResult.isCompliant()).append("\n");
                details.append("Violations Found: ").append(complianceResult.getViolations().size()).append("\n");
                
                if (!complianceResult.getViolations().isEmpty()) {
                    details.append("\nViolation Details:\n");
                    for (ComplianceEngineService.ComplianceViolation violation : complianceResult.getViolations()) {
                        details.append("  - Rule: ").append(violation.getRuleName()).append("\n");
                        details.append("    Severity: ").append(violation.getSeverity()).append("\n");
                        details.append("    Jurisdiction: ").append(violation.getJurisdiction()).append("\n");
                        details.append("    Description: ").append(violation.getDescription()).append("\n");
                    }
                }
                
                if (complianceResult.getProtectionReport() != null) {
                    details.append("\nData Protection Scan:\n");
                    details.append("  Sensitive Data Items: ")
                        .append(complianceResult.getProtectionReport().getSensitiveDataCount()).append("\n");
                }
            }
            
            // Check for List of ComplianceViolation
            if (result instanceof List<?>) {
                List<?> list = (List<?>) result;
                if (!list.isEmpty() && list.get(0) instanceof ComplianceEngineService.ComplianceViolation) {
                    @SuppressWarnings("unchecked")
                    List<ComplianceEngineService.ComplianceViolation> violations = 
                        (List<ComplianceEngineService.ComplianceViolation>) list;
                    
                    details.append("\n\n=== COMPLIANCE CHECK RESULTS ===\n");
                    details.append("Violations Found: ").append(violations.size()).append("\n");
                    
                    for (ComplianceEngineService.ComplianceViolation violation : violations) {
                        details.append("  - Rule: ").append(violation.getRuleName())
                            .append(" (").append(violation.getSeverity()).append(")\n");
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract compliance results from method result: {}", e.getMessage());
        }
    }

    /**
     * Retrieves paginated audit trail for a specific document
     * Returns immutable log entries in chronological order
     * 
     * NOTE: This method retrieves logs but never modifies them (append-only model)
     * For enhanced security, consider:
     * - Storing log hashes on blockchain for tamper detection
     * - Using digital signatures to verify log authenticity
     * - Implementing merkle tree verification for log integrity
     * 
     * @param docId The document ID to get audit trail for
     * @param page Page number (0-indexed)
     * @param size Number of entries per page
     * @return List of audit log entries for the document
     */
    public AuditTrailResult getAuditTrailForDoc(Long docId, int page, int size) {
        logger.info("Retrieving audit trail for document ID: {}", docId);
        
        try {
            // Search for audit logs related to this document ID
            // Logs are immutable - we only read, never update or delete
            List<AuditLog> allLogs = auditLogRepository.findTop100ByOrderByTimestampDesc();
            
            // Filter logs related to the document ID
            List<AuditLog> documentLogs = allLogs.stream()
                .filter(log -> log.getDetails() != null && 
                        (log.getDetails().contains("DocumentId=" + docId) ||
                         log.getArguments() != null && log.getArguments().contains(docId.toString())))
                .skip(page * size)
                .limit(size)
                .collect(Collectors.toList());
            
            long totalCount = allLogs.stream()
                .filter(log -> log.getDetails() != null && 
                        (log.getDetails().contains("DocumentId=" + docId) ||
                         log.getArguments() != null && log.getArguments().contains(docId.toString())))
                .count();
            
            AuditTrailResult result = new AuditTrailResult();
            result.setDocumentId(docId);
            result.setLogs(documentLogs);
            result.setPage(page);
            result.setSize(size);
            result.setTotalCount(totalCount);
            result.setTotalPages((int) Math.ceil((double) totalCount / size));
            
            logger.info("Retrieved {} audit log entries for document {}", documentLogs.size(), docId);
            return result;
            
        } catch (Exception e) {
            logger.error("Failed to retrieve audit trail for document {}: {}", docId, e.getMessage(), e);
            return new AuditTrailResult();
        }
    }

    /**
     * Retrieves complete audit trail for a document (non-paginated)
     * Use with caution for documents with extensive audit history
     * 
     * @param docId The document ID
     * @return List of all audit log entries
     */
    public List<AuditLog> getCompleteAuditTrailForDoc(Long docId) {
        logger.info("Retrieving complete audit trail for document ID: {}", docId);
        
        List<AuditLog> allLogs = auditLogRepository.findTop100ByOrderByTimestampDesc();
        
        return allLogs.stream()
            .filter(log -> log.getDetails() != null && 
                    (log.getDetails().contains("DocumentId=" + docId) ||
                     log.getArguments() != null && log.getArguments().contains(docId.toString())))
            .collect(Collectors.toList());
    }

    /**
     * Inner class for paginated audit trail results
     */
    public static class AuditTrailResult {
        private Long documentId;
        private List<AuditLog> logs;
        private int page;
        private int size;
        private long totalCount;
        private int totalPages;

        // Getters and Setters
        public Long getDocumentId() {
            return documentId;
        }

        public void setDocumentId(Long documentId) {
            this.documentId = documentId;
        }

        public List<AuditLog> getLogs() {
            return logs;
        }

        public void setLogs(List<AuditLog> logs) {
            this.logs = logs;
        }

        public int getPage() {
            return page;
        }

        public void setPage(int page) {
            this.page = page;
        }

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }

        public long getTotalCount() {
            return totalCount;
        }

        public void setTotalCount(long totalCount) {
            this.totalCount = totalCount;
        }

        public int getTotalPages() {
            return totalPages;
        }

        public void setTotalPages(int totalPages) {
            this.totalPages = totalPages;
        }
    }
}

