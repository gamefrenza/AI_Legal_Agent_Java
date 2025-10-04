package com.legalai.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for tracking and managing user sessions
 * Works with Redis-based session management and Spring Security's SessionRegistry
 */
@Service
public class SessionService {

    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    @Autowired
    private SessionRegistry sessionRegistry;

    /**
     * Gets all active sessions for a specific user
     * 
     * @param username The username to get sessions for
     * @return List of active session information
     */
    public List<SessionInformation> getActiveSessionsForUser(String username) {
        logger.debug("Retrieving active sessions for user: {}", username);
        
        List<Object> principals = sessionRegistry.getAllPrincipals();
        
        for (Object principal : principals) {
            if (principal instanceof UserDetails) {
                UserDetails userDetails = (UserDetails) principal;
                if (userDetails.getUsername().equals(username)) {
                    List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
                    logger.info("Found {} active sessions for user: {}", sessions.size(), username);
                    return sessions;
                }
            }
        }
        
        logger.debug("No active sessions found for user: {}", username);
        return List.of();
    }

    /**
     * Gets total count of active sessions across all users
     * 
     * @return Total number of active sessions
     */
    public int getTotalActiveSessions() {
        List<Object> allPrincipals = sessionRegistry.getAllPrincipals();
        
        int totalSessions = 0;
        for (Object principal : allPrincipals) {
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
            totalSessions += sessions.size();
        }
        
        logger.debug("Total active sessions: {}", totalSessions);
        return totalSessions;
    }

    /**
     * Gets list of all currently logged-in users
     * 
     * @return List of usernames with active sessions
     */
    public List<String> getActiveUsers() {
        List<Object> allPrincipals = sessionRegistry.getAllPrincipals();
        
        List<String> activeUsers = allPrincipals.stream()
                .filter(principal -> principal instanceof UserDetails)
                .map(principal -> ((UserDetails) principal).getUsername())
                .filter(username -> !sessionRegistry.getAllSessions(
                        allPrincipals.stream()
                                .filter(p -> p instanceof UserDetails && 
                                        ((UserDetails) p).getUsername().equals(username))
                                .findFirst()
                                .orElse(null), 
                        false).isEmpty())
                .distinct()
                .collect(Collectors.toList());
        
        logger.debug("Active users: {}", activeUsers);
        return activeUsers;
    }

    /**
     * Expires a specific session
     * 
     * @param sessionId The session ID to expire
     * @return true if session was found and expired, false otherwise
     */
    public boolean expireSession(String sessionId) {
        logger.info("Attempting to expire session: {}", sessionId);
        
        List<Object> allPrincipals = sessionRegistry.getAllPrincipals();
        
        for (Object principal : allPrincipals) {
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
            
            for (SessionInformation session : sessions) {
                if (session.getSessionId().equals(sessionId)) {
                    session.expireNow();
                    
                    String username = principal instanceof UserDetails ? 
                            ((UserDetails) principal).getUsername() : "unknown";
                    
                    auditLogger.warn("SESSION_EXPIRED: User={}, SessionId={}, ExpiredBy=SYSTEM",
                            username, sessionId);
                    logger.info("Session expired: {} for user: {}", sessionId, username);
                    return true;
                }
            }
        }
        
        logger.warn("Session not found: {}", sessionId);
        return false;
    }

    /**
     * Expires all sessions for a specific user
     * 
     * @param username The username whose sessions should be expired
     * @return Number of sessions expired
     */
    public int expireAllSessionsForUser(String username) {
        logger.info("Expiring all sessions for user: {}", username);
        
        List<SessionInformation> sessions = getActiveSessionsForUser(username);
        int expiredCount = 0;
        
        for (SessionInformation session : sessions) {
            session.expireNow();
            expiredCount++;
            auditLogger.warn("SESSION_EXPIRED: User={}, SessionId={}, ExpiredBy=ADMIN",
                    username, session.getSessionId());
        }
        
        logger.info("Expired {} sessions for user: {}", expiredCount, username);
        return expiredCount;
    }

    /**
     * Scheduled task to clean up expired sessions and log inactive sessions
     * Runs every 5 minutes
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void cleanupExpiredSessions() {
        logger.debug("Running scheduled session cleanup task");
        
        List<Object> allPrincipals = sessionRegistry.getAllPrincipals();
        int expiredCount = 0;
        int inactiveCount = 0;
        
        for (Object principal : allPrincipals) {
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, true);
            
            String username = principal instanceof UserDetails ? 
                    ((UserDetails) principal).getUsername() : "unknown";
            
            for (SessionInformation session : sessions) {
                if (session.isExpired()) {
                    expiredCount++;
                    logger.debug("Cleaned up expired session: {} for user: {}", 
                            session.getSessionId(), username);
                } else if (isSessionInactive(session)) {
                    inactiveCount++;
                    session.expireNow();
                    auditLogger.info("SESSION_EXPIRED_INACTIVE: User={}, SessionId={}, LastRequest={}",
                            username, session.getSessionId(), session.getLastRequest());
                    logger.info("Expired inactive session: {} for user: {}", 
                            session.getSessionId(), username);
                }
            }
        }
        
        if (expiredCount > 0 || inactiveCount > 0) {
            logger.info("Session cleanup complete. Expired: {}, Inactive: {}", 
                    expiredCount, inactiveCount);
        }
    }

    /**
     * Checks if a session is inactive (no activity for more than 30 minutes)
     * 
     * @param session The session to check
     * @return true if session is inactive
     */
    private boolean isSessionInactive(SessionInformation session) {
        if (session.getLastRequest() == null) {
            return false;
        }
        
        // Consider session inactive if no activity for 30 minutes
        long inactiveThresholdMillis = 30 * 60 * 1000; // 30 minutes
        long lastRequestTime = session.getLastRequest().getTime();
        long currentTime = System.currentTimeMillis();
        
        return (currentTime - lastRequestTime) > inactiveThresholdMillis;
    }

    /**
     * Gets detailed session information for monitoring
     * 
     * @return Formatted string with session statistics
     */
    public String getSessionStatistics() {
        int totalSessions = getTotalActiveSessions();
        List<String> activeUsers = getActiveUsers();
        
        StringBuilder stats = new StringBuilder();
        stats.append("Session Statistics:\n");
        stats.append("Total Active Sessions: ").append(totalSessions).append("\n");
        stats.append("Active Users: ").append(activeUsers.size()).append("\n");
        stats.append("Users: ").append(String.join(", ", activeUsers)).append("\n");
        
        return stats.toString();
    }
}

