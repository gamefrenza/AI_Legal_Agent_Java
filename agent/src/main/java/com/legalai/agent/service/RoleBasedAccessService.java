package com.legalai.agent.service;

import com.legalai.agent.entity.Document;
import com.legalai.agent.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for enforcing role-based access control (RBAC)
 * Integrates with Spring Security's SecurityContext to verify user permissions
 */
@Service
public class RoleBasedAccessService {

    private static final Logger logger = LoggerFactory.getLogger(RoleBasedAccessService.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    @Autowired
    private DocumentRepository documentRepository;

    /**
     * Checks if the current authenticated user can access a document based on required role
     * 
     * @param docId The document ID to check access for
     * @param requiredRole The role required to access the document (e.g., "ROLE_LAWYER", "ROLE_ADMIN")
     * @return true if user has required role, false otherwise
     * @throws AccessDeniedException if user does not have required role
     */
    public boolean canAccessDocument(Long docId, String requiredRole) throws AccessDeniedException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated()) {
            auditLogger.warn("ACCESS_DENIED: Unauthenticated user attempted to access document ID={}", docId);
            throw new AccessDeniedException("User is not authenticated");
        }

        String username = auth.getName();
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        
        // Verify document exists
        Optional<Document> document = documentRepository.findById(docId);
        if (document.isEmpty()) {
            logger.warn("Document not found: ID={}", docId);
            throw new IllegalArgumentException("Document not found with ID: " + docId);
        }

        // Normalize role format (ensure it starts with ROLE_)
        String normalizedRole = normalizeRole(requiredRole);
        
        // Check if user has required role
        boolean hasRole = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals(normalizedRole));

        if (!hasRole) {
            String userRoles = authorities.stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.joining(", "));
            
            auditLogger.warn("ACCESS_DENIED: User={}, UserRoles=[{}], RequiredRole={}, DocumentId={}, FileName={}",
                    username, userRoles, normalizedRole, docId, document.get().getFileName());
            
            throw new AccessDeniedException(
                String.format("User '%s' with roles [%s] does not have required role '%s' to access document ID %d",
                        username, userRoles, normalizedRole, docId)
            );
        }

        auditLogger.info("ACCESS_GRANTED: User={}, RequiredRole={}, DocumentId={}, FileName={}",
                username, normalizedRole, docId, document.get().getFileName());
        
        logger.debug("Access granted for user {} to document ID={}", username, docId);
        return true;
    }

    /**
     * Checks if the current user has any of the specified roles
     * 
     * @param docId The document ID to check access for
     * @param requiredRoles Array of roles, any of which grants access
     * @return true if user has at least one of the required roles
     * @throws AccessDeniedException if user does not have any of the required roles
     */
    public boolean canAccessDocumentWithAnyRole(Long docId, String... requiredRoles) throws AccessDeniedException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated()) {
            auditLogger.warn("ACCESS_DENIED: Unauthenticated user attempted to access document ID={}", docId);
            throw new AccessDeniedException("User is not authenticated");
        }

        String username = auth.getName();
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        
        // Verify document exists
        Optional<Document> document = documentRepository.findById(docId);
        if (document.isEmpty()) {
            logger.warn("Document not found: ID={}", docId);
            throw new IllegalArgumentException("Document not found with ID: " + docId);
        }

        // Check if user has any of the required roles
        for (String role : requiredRoles) {
            String normalizedRole = normalizeRole(role);
            boolean hasRole = authorities.stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(authority -> authority.equals(normalizedRole));
            
            if (hasRole) {
                auditLogger.info("ACCESS_GRANTED: User={}, MatchedRole={}, DocumentId={}, FileName={}",
                        username, normalizedRole, docId, document.get().getFileName());
                return true;
            }
        }

        // User doesn't have any of the required roles
        String userRoles = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(", "));
        
        String requiredRolesStr = String.join(", ", requiredRoles);
        
        auditLogger.warn("ACCESS_DENIED: User={}, UserRoles=[{}], RequiredRoles=[{}], DocumentId={}, FileName={}",
                username, userRoles, requiredRolesStr, docId, document.get().getFileName());
        
        throw new AccessDeniedException(
            String.format("User '%s' with roles [%s] does not have any of the required roles [%s] to access document ID %d",
                    username, userRoles, requiredRolesStr, docId)
        );
    }

    /**
     * Checks if the current user has a specific role
     * Does not check document-specific permissions
     * 
     * @param requiredRole The role to check for
     * @return true if user has the role, false otherwise
     */
    public boolean hasRole(String requiredRole) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }

        String normalizedRole = normalizeRole(requiredRole);
        
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals(normalizedRole));
    }

    /**
     * Checks if the current user has any of the specified roles
     * 
     * @param roles Roles to check for
     * @return true if user has at least one of the roles
     */
    public boolean hasAnyRole(String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }

        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        
        for (String role : roles) {
            String normalizedRole = normalizeRole(role);
            boolean hasRole = authorities.stream()
                    .map(GrantedAuthority::getAuthority)
                    .anyMatch(authority -> authority.equals(normalizedRole));
            
            if (hasRole) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Gets the current authenticated username
     * 
     * @return Username or "anonymous" if not authenticated
     */
    public String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth.getName() : "anonymous";
    }

    /**
     * Gets all roles of the current authenticated user
     * 
     * @return Collection of role strings
     */
    public Collection<String> getCurrentUserRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated()) {
            return java.util.Collections.emptyList();
        }

        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
    }

    /**
     * Normalizes role string to ensure it starts with "ROLE_" prefix
     * 
     * @param role The role string to normalize
     * @return Normalized role string with ROLE_ prefix
     */
    private String normalizeRole(String role) {
        if (role == null || role.isEmpty()) {
            return "ROLE_USER";
        }
        
        if (role.startsWith("ROLE_")) {
            return role;
        }
        
        return "ROLE_" + role;
    }
}

