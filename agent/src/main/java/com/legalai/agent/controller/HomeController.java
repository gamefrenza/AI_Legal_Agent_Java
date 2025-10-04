package com.legalai.agent.controller;

import com.legalai.agent.entity.Document;
import com.legalai.agent.service.DocumentService;
import com.legalai.agent.service.LegalAiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Home Controller for Thymeleaf-based web interface
 * Provides simple upload form at root path
 */
@Controller
public class HomeController {

    private static final Logger logger = LoggerFactory.getLogger(HomeController.class);

    @Autowired
    private DocumentService documentService;

    @Autowired
    private LegalAiService legalAiService;

    /**
     * Display upload form at root path
     */
    @GetMapping("/")
    public String index(Model model, Authentication auth) {
        if (auth != null) {
            logger.info("User {} accessing home page", auth.getName());
        }
        return "index";
    }

    /**
     * Handle document upload from Thymeleaf form
     */
    @PostMapping("/upload")
    public String uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("jurisdiction") String jurisdiction,
            @RequestParam(value = "analyze", required = false) String analyze,
            Authentication auth,
            RedirectAttributes redirectAttributes) {
        
        logger.info("Upload request from Thymeleaf: file={}, jurisdiction={}, user={}", 
                file.getOriginalFilename(), jurisdiction, auth.getName());
        
        try {
            // Upload with compliance check
            DocumentService.DocumentComplianceResult result = 
                    documentService.secureStoreDocumentWithCompliance(file, jurisdiction);
            
            StringBuilder message = new StringBuilder();
            message.append("Document uploaded successfully! ");
            message.append("File: ").append(file.getOriginalFilename()).append(". ");
            
            if (!result.isCompliant()) {
                message.append("⚠️ Compliance issues detected: ")
                       .append(result.getViolations().size())
                       .append(" violation(s). ");
            } else {
                message.append("✅ Document is compliant. ");
            }
            
            if (result.getProtectionReport().getSensitiveDataCount() > 0) {
                message.append("🔒 Protected ")
                       .append(result.getProtectionReport().getSensitiveDataCount())
                       .append(" sensitive data item(s). ");
            }
            
            // Run AI analysis if requested
            if ("on".equals(analyze)) {
                try {
                    String decryptedText = result.getDocument().decryptContent();
                    LegalAiService.ContractAnalysisResult analysisResult = 
                            legalAiService.analyzeContract(decryptedText, jurisdiction);
                    
                    message.append("🤖 AI Analysis complete: ")
                           .append(analysisResult.getRisks().size())
                           .append(" risk(s) identified, ")
                           .append(analysisResult.getAmbiguities().size())
                           .append(" ambiguity(ies) found.");
                } catch (Exception e) {
                    logger.error("AI analysis failed: {}", e.getMessage(), e);
                    message.append("⚠️ AI analysis failed: ").append(e.getMessage());
                }
            }
            
            redirectAttributes.addFlashAttribute("message", message.toString());
            
        } catch (Exception e) {
            logger.error("Upload failed: {}", e.getMessage(), e);
            redirectAttributes.addFlashAttribute("error", 
                    "Upload failed: " + e.getMessage());
        }
        
        return "redirect:/";
    }

    /**
     * Login page (if needed)
     */
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}

