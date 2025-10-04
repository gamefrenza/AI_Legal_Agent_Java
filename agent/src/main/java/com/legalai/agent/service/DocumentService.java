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
import java.util.Optional;

@Service
public class DocumentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentService.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    @Autowired
    private DocumentRepository documentRepository;

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
     * 2. Encrypting the text using the Document entity's encrypt method
     * 3. Saving to repository
     * 4. Logging the action for audit
     * 
     * @param file The multipart file to store
     * @return The document entity (not yet saved to database)
     * @throws Exception if parsing or encryption fails
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

        // Step 2: Create Document entity
        Document document = new Document();
        document.setFileName(fileName);

        // Step 3: Encrypt the extracted text using Document entity's encrypt method
        try {
            document.encryptContent(extractedText);
            logger.debug("Successfully encrypted content for file: {}", fileName);
        } catch (Exception e) {
            logger.error("Failed to encrypt document content: {}", fileName, e);
            auditLogger.error("DOCUMENT_ENCRYPTION_FAILED: FileName={}, Error={}", fileName, e.getMessage());
            throw new Exception("Failed to encrypt document content", e);
        }

        // Step 4: Log the action for audit
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null) ? auth.getName() : "SYSTEM";
        
        auditLogger.info("DOCUMENT_SECURED: User={}, FileName={}, OriginalSize={} bytes, EncryptedSize={} bytes, Timestamp={}",
                username, fileName, file.getSize(), 
                document.getEncryptedContent().length(), LocalDateTime.now());

        logger.info("Document secured successfully: {}", fileName);
        return document;
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

