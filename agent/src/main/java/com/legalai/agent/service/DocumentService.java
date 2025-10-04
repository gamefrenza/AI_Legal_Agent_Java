package com.legalai.agent.service;

import com.legalai.agent.entity.Document;
import com.legalai.agent.repository.DocumentRepository;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private RoleBasedAccessService roleBasedAccessService;

    @Autowired
    private ComplianceEngineService complianceEngineService;

    private final Tika tika = new Tika();

    /**
     * Uploads a document with RBAC verification
     * Verifies user has LAWYER role before allowing upload
     * 
     * @param file The multipart file to upload
     * @param jurisdiction The jurisdiction for the document
     * @param auth Authentication object containing user details
     * @return The saved document
     * @throws Exception if upload fails or user is unauthorized
     */
    @PreAuthorize("hasRole('LAWYER')")
    @Transactional
    public Document uploadDocument(MultipartFile file, String jurisdiction, Authentication auth) throws Exception {
        logger.info("Upload request for file: {} by user: {}", file.getOriginalFilename(), auth.getName());

        // Additional manual verification via SecurityContextHolder
        Authentication contextAuth = SecurityContextHolder.getContext().getAuthentication();
        if (!hasRequiredRole(contextAuth, "ROLE_LAWYER")) {
            auditLogger.warn("UNAUTHORIZED_UPLOAD_ATTEMPT: User {} attempted to upload {} without LAWYER role",
                    contextAuth.getName(), file.getOriginalFilename());
            throw new SecurityException("User does not have required LAWYER role to upload documents");
        }

        // Verify file is not empty
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Cannot upload empty file");
        }

        // Create document and use secure storage
        Document document = secureStoreDocument(file);
        document.setJurisdiction(jurisdiction);
        document.setVersion(1); // Initial version

        Document savedDocument = documentRepository.save(document);

        auditLogger.info("DOCUMENT_UPLOADED: User={}, DocumentId={}, FileName={}, Jurisdiction={}, Size={} bytes",
                auth.getName(), savedDocument.getId(), savedDocument.getFileName(),
                savedDocument.getJurisdiction(), file.getSize());

        logger.info("Successfully uploaded document: {} with ID: {}", savedDocument.getFileName(), savedDocument.getId());
        return savedDocument;
    }

    /**
     * Securely stores a document by:
     * 1. Parsing content with Apache Tika
     * 2. Running data protection scan and compliance checks
     * 3. Encrypting the text using the Document entity's encrypt method
     * 4. Saving to repository
     * 5. Logging the action for audit
     * 
     * @param file The multipart file to store
     * @return The document entity (not yet saved to database)
     * @throws Exception if parsing, compliance, or encryption fails
     */
    @Transactional
    public Document secureStoreDocument(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename();
        logger.info("Starting secure storage process for file: {}", fileName);

        // Step 1: Parse document content with Apache Tika
        String extractedText;
        try (InputStream inputStream = file.getInputStream()) {
            extractedText = tika.parseToString(inputStream);
            logger.debug("Extracted {} characters from file: {}", extractedText.length(), fileName);
        } catch (IOException | TikaException e) {
            logger.error("Failed to parse file with Tika: {}", fileName, e);
            auditLogger.error("DOCUMENT_PARSE_FAILED: FileName={}, Error={}", fileName, e.getMessage());
            throw new Exception("Failed to parse document content", e);
        }

        // Step 2: Run data protection scan to detect and mask sensitive data
        ComplianceEngineService.DataProtectionReport protectionReport = 
                complianceEngineService.dataProtectionScan(extractedText);
        
        if (protectionReport.getSensitiveDataCount() > 0) {
            logger.warn("Data protection scan found {} sensitive items in file: {}", 
                    protectionReport.getSensitiveDataCount(), fileName);
            auditLogger.warn("SENSITIVE_DATA_DETECTED: FileName={}, SensitiveItems={}, Types={}",
                    fileName, protectionReport.getSensitiveDataCount(),
                    protectionReport.getSensitiveDataMatches().stream()
                        .map(ComplianceEngineService.SensitiveDataMatch::getType)
                        .distinct().toArray());
            
            // Use protected text instead of original
            extractedText = protectionReport.getProtectedText();
        }

        // Step 3: Create Document entity
        Document document = new Document();
        document.setFileName(fileName);

        // Step 4: Encrypt the extracted (and protected) text using Document entity's encrypt method
        try {
            document.encryptContent(extractedText);
            logger.debug("Successfully encrypted content for file: {}", fileName);
        } catch (Exception e) {
            logger.error("Failed to encrypt document content: {}", fileName, e);
            auditLogger.error("DOCUMENT_ENCRYPTION_FAILED: FileName={}, Error={}", fileName, e.getMessage());
            throw new Exception("Failed to encrypt document content", e);
        }

        // Step 5: Log the action for audit
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null) ? auth.getName() : "SYSTEM";
        
        auditLogger.info("DOCUMENT_SECURED: User={}, FileName={}, OriginalSize={} bytes, EncryptedSize={} bytes, SensitiveDataMasked={}, Timestamp={}",
                username, fileName, file.getSize(), 
                document.getEncryptedContent().length(),
                protectionReport.getSensitiveDataCount(),
                LocalDateTime.now());

        logger.info("Document secured successfully: {}", fileName);
        return document;
    }

    /**
     * Securely stores a document with explicit compliance check against jurisdiction rules
     * 
     * @param file The multipart file to store
     * @param jurisdiction The jurisdiction to check compliance against
     * @return The document entity with compliance report
     * @throws Exception if compliance violations are found or storage fails
     */
    @Transactional
    public DocumentComplianceResult secureStoreDocumentWithCompliance(MultipartFile file, String jurisdiction) throws Exception {
        String fileName = file.getOriginalFilename();
        logger.info("Starting secure storage with compliance check for file: {} in jurisdiction: {}", fileName, jurisdiction);

        // Parse document content
        String extractedText;
        try (InputStream inputStream = file.getInputStream()) {
            extractedText = tika.parseToString(inputStream);
        } catch (IOException | TikaException e) {
            throw new Exception("Failed to parse document content", e);
        }

        // Run compliance check
        List<ComplianceEngineService.ComplianceViolation> violations = 
                complianceEngineService.checkCompliance(extractedText, jurisdiction);
        
        // Run data protection scan
        ComplianceEngineService.DataProtectionReport protectionReport = 
                complianceEngineService.dataProtectionScan(extractedText);
        
        // Store document with protected text
        Document document = new Document();
        document.setFileName(fileName);
        document.setJurisdiction(jurisdiction);
        document.encryptContent(protectionReport.getProtectedText());
        
        // Log compliance results
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null) ? auth.getName() : "SYSTEM";
        
        auditLogger.warn("COMPLIANCE_CHECK_COMPLETE: User={}, FileName={}, Jurisdiction={}, Violations={}, SensitiveData={}, Timestamp={}",
                username, fileName, jurisdiction, violations.size(), 
                protectionReport.getSensitiveDataCount(), LocalDateTime.now());
        
        // Return result
        DocumentComplianceResult result = new DocumentComplianceResult();
        result.setDocument(document);
        result.setViolations(violations);
        result.setProtectionReport(protectionReport);
        result.setCompliant(violations.isEmpty());
        
        return result;
    }

    /**
     * Inner class for document compliance results
     */
    public static class DocumentComplianceResult {
        private Document document;
        private List<ComplianceEngineService.ComplianceViolation> violations;
        private ComplianceEngineService.DataProtectionReport protectionReport;
        private boolean compliant;

        // Getters and Setters
        public Document getDocument() {
            return document;
        }

        public void setDocument(Document document) {
            this.document = document;
        }

        public List<ComplianceEngineService.ComplianceViolation> getViolations() {
            return violations;
        }

        public void setViolations(List<ComplianceEngineService.ComplianceViolation> violations) {
            this.violations = violations;
        }

        public ComplianceEngineService.DataProtectionReport getProtectionReport() {
            return protectionReport;
        }

        public void setProtectionReport(ComplianceEngineService.DataProtectionReport protectionReport) {
            this.protectionReport = protectionReport;
        }

        public boolean isCompliant() {
            return compliant;
        }

        public void setCompliant(boolean compliant) {
            this.compliant = compliant;
        }
    }

    /**
     * Retrieves a document by ID with audit logging
     * 
     * @param id The document ID
     * @return Optional containing the document if found
     */
    @PreAuthorize("hasAnyRole('LAWYER', 'CLERK', 'ADMIN')")
    public Optional<Document> getDocumentById(Long id) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        logger.info("Document retrieval request: ID={} by user={}", id, auth.getName());

        Optional<Document> document = documentRepository.findById(id);
        
        if (document.isPresent()) {
            auditLogger.info("DOCUMENT_ACCESSED: User={}, DocumentId={}, FileName={}",
                    auth.getName(), id, document.get().getFileName());
        } else {
            logger.warn("Document not found: ID={}", id);
        }

        return document;
    }

    /**
     * Retrieves a document by ID with explicit RBAC enforcement
     * Uses RoleBasedAccessService to verify user has required role
     * 
     * @param id The document ID
     * @param requiredRole The role required to access the document (e.g., "LAWYER", "ADMIN")
     * @return Optional containing the document if found and user has access
     * @throws org.springframework.security.access.AccessDeniedException if user lacks required role
     */
    public Optional<Document> getDocumentByIdWithRoleCheck(Long id, String requiredRole) {
        // Enforce RBAC using RoleBasedAccessService
        roleBasedAccessService.canAccessDocument(id, requiredRole);
        
        // If no exception was thrown, user has access
        return documentRepository.findById(id);
    }

    /**
     * Retrieves a document by ID with flexible role requirements
     * User needs any ONE of the specified roles to access
     * 
     * @param id The document ID
     * @param requiredRoles Array of roles, any of which grants access
     * @return Optional containing the document if found and user has access
     * @throws org.springframework.security.access.AccessDeniedException if user lacks any required role
     */
    public Optional<Document> getDocumentByIdWithAnyRole(Long id, String... requiredRoles) {
        // Enforce RBAC using RoleBasedAccessService
        roleBasedAccessService.canAccessDocumentWithAnyRole(id, requiredRoles);
        
        // If no exception was thrown, user has access
        return documentRepository.findById(id);
    }

    /**
     * Deletes a document by ID - restricted to ADMIN role only
     * 
     * @param id The document ID to delete
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void deleteDocument(Long id) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        logger.info("Document deletion request: ID={} by user={}", id, auth.getName());

        Optional<Document> document = documentRepository.findById(id);
        
        if (document.isPresent()) {
            documentRepository.deleteById(id);
            auditLogger.warn("DOCUMENT_DELETED: User={}, DocumentId={}, FileName={}",
                    auth.getName(), id, document.get().getFileName());
            logger.info("Document deleted: ID={}", id);
        } else {
            logger.warn("Attempted to delete non-existent document: ID={}", id);
        }
    }

    /**
     * Deletes a document with explicit RBAC enforcement
     * Uses RoleBasedAccessService to verify user has ADMIN role
     * 
     * @param id The document ID to delete
     * @throws org.springframework.security.access.AccessDeniedException if user is not ADMIN
     */
    @Transactional
    public void deleteDocumentWithRoleCheck(Long id) {
        // Enforce RBAC - only ADMIN can delete
        roleBasedAccessService.canAccessDocument(id, "ADMIN");
        
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Optional<Document> document = documentRepository.findById(id);
        
        if (document.isPresent()) {
            documentRepository.deleteById(id);
            auditLogger.warn("DOCUMENT_DELETED: User={}, DocumentId={}, FileName={}",
                    auth.getName(), id, document.get().getFileName());
            logger.info("Document deleted: ID={}", id);
        } else {
            logger.warn("Attempted to delete non-existent document: ID={}", id);
        }
    }

    /**
     * Helper method to check if authentication has required role
     * 
     * @param auth Authentication object
     * @param role Role to check for
     * @return true if user has the role
     */
    private boolean hasRequiredRole(Authentication auth, String role) {
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }

        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        return authorities.stream()
                .anyMatch(authority -> authority.getAuthority().equals(role));
    }
}

