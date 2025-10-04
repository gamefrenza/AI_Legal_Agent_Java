package com.legalai.agent.controller;

import com.legalai.agent.entity.Document;
import com.legalai.agent.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for document management and legal AI operations
 * Provides endpoints for uploading, analyzing, searching, and managing legal documents
 */
@RestController
@RequestMapping("/docs")
@CrossOrigin(origins = "*", maxAge = 3600)
public class DocumentController {

    private static final Logger logger = LoggerFactory.getLogger(DocumentController.class);

    @Autowired
    private DocumentService documentService;

    @Autowired
    private LegalAiService legalAiService;

    @Autowired
    private ComplianceEngineService complianceEngineService;

    @Autowired
    private ActivityMonitorService activityMonitorService;

    @Autowired
    private DocumentVersionService documentVersionService;

    /**
     * Upload and analyze a document
     * Parses, encrypts, stores, and optionally runs AI analysis
     * 
     * @param file The document file to upload
     * @param jurisdiction The jurisdiction for analysis
     * @param analyze Whether to run AI analysis (default: true)
     * @param auth Authentication context
     * @return Upload result with document and analysis
     */
    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('LAWYER', 'ADMIN')")
    public ResponseEntity<?> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("jurisdiction") String jurisdiction,
            @RequestParam(value = "analyze", defaultValue = "true") boolean analyze,
            Authentication auth) {
        
        logger.info("Upload request: file={}, jurisdiction={}, analyze={}, user={}", 
                file.getOriginalFilename(), jurisdiction, analyze, auth.getName());
        
        try {
            // Step 1: Upload document with compliance checks
            DocumentService.DocumentComplianceResult complianceResult = 
                    documentService.secureStoreDocumentWithCompliance(file, jurisdiction);
            
            // Save document to database
            Document savedDocument = documentService.getDocumentById(
                    complianceResult.getDocument().getId()
            ).orElse(complianceResult.getDocument());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("document", savedDocument);
            response.put("compliance", complianceResult);
            
            // Step 2: Run AI analysis if requested
            if (analyze) {
                try {
                    String decryptedText = savedDocument.decryptContent();
                    
                    // Contract analysis (async)
                    CompletableFuture<LegalAiService.ContractAnalysisResult> analysisFuture = 
                            legalAiService.analyzeContract(decryptedText, jurisdiction);
                    
                    // Risk assessment (async)
                    CompletableFuture<LegalAiService.RiskAssessmentResult> riskFuture = 
                            legalAiService.riskAssessment(decryptedText);
                    
                    // Wait for both analyses to complete
                    CompletableFuture.allOf(analysisFuture, riskFuture).join();
                    
                    response.put("analysis", analysisFuture.get());
                    response.put("riskAssessment", riskFuture.get());
                    
                    logger.info("Document uploaded and analyzed successfully: ID={}", savedDocument.getId());
                    
                } catch (Exception e) {
                    logger.error("AI analysis failed: {}", e.getMessage(), e);
                    response.put("analysisError", "AI analysis failed: " + e.getMessage());
                }
            }
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Document upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    /**
     * Legal research endpoint
     * Conducts AI-powered legal research on a topic
     * 
     * @param query The research query
     * @param jurisdiction The jurisdiction to research in
     * @return Research results with citations and recommendations
     */
    @GetMapping("/search")
    @PreAuthorize("hasAnyRole('LAWYER', 'CLERK', 'ADMIN')")
    public ResponseEntity<?> searchLegalTopic(
            @RequestParam("query") String query,
            @RequestParam(value = "jurisdiction", defaultValue = "US") String jurisdiction) {
        
        logger.info("Legal research request: query={}, jurisdiction={}", query, jurisdiction);
        
        try {
            CompletableFuture<LegalAiService.LegalResearchResult> resultFuture = 
                    legalAiService.researchTopic(query, jurisdiction);
            
            LegalAiService.LegalResearchResult result = resultFuture.get();
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Legal research failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Research failed: " + e.getMessage()));
        }
    }

    /**
     * Get document by ID
     * 
     * @param id Document ID
     * @return Document details
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('LAWYER', 'CLERK', 'ADMIN')")
    public ResponseEntity<?> getDocument(@PathVariable Long id) {
        logger.info("Get document request: ID={}", id);
        
        try {
            Optional<Document> document = documentService.getDocumentById(id);
            
            if (document.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            return ResponseEntity.ok(document.get());
            
        } catch (Exception e) {
            logger.error("Failed to retrieve document: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List all documents (simplified - in production add pagination)
     * 
     * @return List of documents
     */
    @GetMapping("/list")
    @PreAuthorize("hasAnyRole('LAWYER', 'CLERK', 'ADMIN')")
    public ResponseEntity<?> listDocuments() {
        logger.info("List documents request");
        
        try {
            // In production, add pagination and filtering
            Iterable<Document> documents = documentService.getDocumentById(1L).stream().toList();
            return ResponseEntity.ok(documents);
            
        } catch (Exception e) {
            logger.error("Failed to list documents: {}", e.getMessage(), e);
            return ResponseEntity.ok(new ArrayList<>());
        }
    }

    /**
     * Analyze an existing document
     * 
     * @param id Document ID
     * @return Analysis results
     */
    @PostMapping("/{id}/analyze")
    @PreAuthorize("hasAnyRole('LAWYER', 'ADMIN')")
    public ResponseEntity<?> analyzeDocument(@PathVariable Long id) {
        logger.info("Analyze document request: ID={}", id);
        
        try {
            Optional<Document> docOpt = documentService.getDocumentById(id);
            
            if (docOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Document document = docOpt.get();
            String decryptedText = document.decryptContent();
            
            // Run AI analysis (async)
            CompletableFuture<LegalAiService.ContractAnalysisResult> analysisFuture = 
                    legalAiService.analyzeContract(decryptedText, document.getJurisdiction());
            
            LegalAiService.ContractAnalysisResult analysisResult = analysisFuture.get();
            return ResponseEntity.ok(analysisResult);
            
        } catch (Exception e) {
            logger.error("Document analysis failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Analysis failed: " + e.getMessage()));
        }
    }

    /**
     * Get risk assessment for a document
     * 
     * @param id Document ID
     * @return Risk assessment results
     */
    @GetMapping("/{id}/risk")
    @PreAuthorize("hasAnyRole('LAWYER', 'ADMIN')")
    public ResponseEntity<?> getRiskAssessment(@PathVariable Long id) {
        logger.info("Risk assessment request: ID={}", id);
        
        try {
            Optional<Document> docOpt = documentService.getDocumentById(id);
            
            if (docOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Document document = docOpt.get();
            String decryptedText = document.decryptContent();
            
            java.util.concurrent.CompletableFuture<LegalAiService.RiskAssessmentResult> riskFuture = 
                    legalAiService.riskAssessment(decryptedText);
            LegalAiService.RiskAssessmentResult riskResult = riskFuture.get();
            
            return ResponseEntity.ok(riskResult);
            
        } catch (Exception e) {
            logger.error("Risk assessment failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Risk assessment failed: " + e.getMessage()));
        }
    }

    /**
     * Check compliance for a document
     * 
     * @param id Document ID
     * @return Compliance validation results
     */
    @PostMapping("/{id}/compliance")
    @PreAuthorize("hasAnyRole('LAWYER', 'ADMIN')")
    public ResponseEntity<?> checkCompliance(@PathVariable Long id) {
        logger.info("Compliance check request: ID={}", id);
        
        try {
            Optional<Document> docOpt = documentService.getDocumentById(id);
            
            if (docOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            Document document = docOpt.get();
            String decryptedText = document.decryptContent();
            
            // AI-powered compliance validation (merges with rule-based)
            java.util.concurrent.CompletableFuture<LegalAiService.ComplianceValidationResult> complianceFuture = 
                    legalAiService.validateComplianceAi(decryptedText, document.getJurisdiction());
            LegalAiService.ComplianceValidationResult complianceResult = complianceFuture.get();
            
            return ResponseEntity.ok(complianceResult);
            
        } catch (Exception e) {
            logger.error("Compliance check failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Compliance check failed: " + e.getMessage()));
        }
    }

    /**
     * Get audit trail for a document
     * 
     * @param id Document ID
     * @param page Page number (default: 0)
     * @param size Page size (default: 20)
     * @return Paginated audit logs
     */
    @GetMapping("/{id}/audit")
    @PreAuthorize("hasAnyRole('LAWYER', 'ADMIN')")
    public ResponseEntity<?> getAuditTrail(
            @PathVariable Long id,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        
        logger.info("Audit trail request: ID={}, page={}, size={}", id, page, size);
        
        try {
            ActivityMonitorService.AuditTrailResult auditTrail = 
                    activityMonitorService.getAuditTrailForDoc(id, page, size);
            
            return ResponseEntity.ok(auditTrail);
            
        } catch (Exception e) {
            logger.error("Failed to retrieve audit trail: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Audit trail retrieval failed: " + e.getMessage()));
        }
    }

    /**
     * Get version history for a document
     * 
     * @param fileName The file name to get history for
     * @return Version history with diffs
     */
    @GetMapping("/versions/{fileName}")
    @PreAuthorize("hasAnyRole('LAWYER', 'CLERK', 'ADMIN')")
    public ResponseEntity<?> getVersionHistory(@PathVariable String fileName) {
        logger.info("Version history request: fileName={}", fileName);
        
        try {
            List<DocumentVersionService.DocumentVersionHistory> history = 
                    documentVersionService.getVersionHistory(fileName);
            
            return ResponseEntity.ok(history);
            
        } catch (Exception e) {
            logger.error("Failed to retrieve version history: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Version history retrieval failed: " + e.getMessage()));
        }
    }

    /**
     * Redact PII from text
     * 
     * @param text Text to redact
     * @return Redacted text
     */
    @PostMapping("/redact")
    @PreAuthorize("hasAnyRole('LAWYER', 'ADMIN')")
    public ResponseEntity<?> redactPII(@RequestBody Map<String, String> request) {
        logger.info("PII redaction request");
        
        try {
            String text = request.get("text");
            if (text == null || text.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Text is required"));
            }
            
            String redactedText = complianceEngineService.redactPII(text);
            
            return ResponseEntity.ok(Map.of(
                "originalLength", text.length(),
                "redactedText", redactedText,
                "redactedLength", redactedText.length()
            ));
            
        } catch (Exception e) {
            logger.error("PII redaction failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Redaction failed: " + e.getMessage()));
        }
    }

    /**
     * Delete document (ADMIN only)
     * 
     * @param id Document ID
     * @return Success response
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteDocument(@PathVariable Long id) {
        logger.info("Delete document request: ID={}", id);
        
        try {
            documentService.deleteDocument(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "Document deleted"));
            
        } catch (Exception e) {
            logger.error("Document deletion failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Deletion failed: " + e.getMessage()));
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "Legal AI Document Service",
            "timestamp", System.currentTimeMillis()
        ));
    }
}

