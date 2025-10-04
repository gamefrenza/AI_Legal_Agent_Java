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
import java.util.stream.Collectors;

/**
 * AOP-based activity monitoring service that logs all service method calls
 * Uses Spring AOP @After advice to capture method execution details
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
            
            auditLog.setDetails(details.toString());
            
            // Save to database asynchronously
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
}

