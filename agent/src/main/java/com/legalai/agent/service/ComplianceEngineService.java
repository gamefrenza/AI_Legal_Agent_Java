package com.legalai.agent.service;

import com.legalai.agent.entity.ComplianceRule;
import com.legalai.agent.repository.ComplianceRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compliance Engine Service for validating documents against regulatory rules
 * Supports GDPR, CCPA, HIPAA, and other jurisdiction-specific regulations
 */
@Service
public class ComplianceEngineService {

    private static final Logger logger = LoggerFactory.getLogger(ComplianceEngineService.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    @Autowired
    private ComplianceRuleRepository complianceRuleRepository;

    @Value("${openai.api-key:}")
    private String openaiApiKey;

    // PII Detection Patterns
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
    );
    
    private static final Pattern SSN_PATTERN = Pattern.compile(
        "\\b\\d{3}-\\d{2}-\\d{4}\\b"
    );
    
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "\\b\\d{3}[-.]?\\d{3}[-.]?\\d{4}\\b"
    );
    
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
        "\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b"
    );

    /**
     * Checks document text for compliance violations against jurisdiction-specific rules
     * Uses regex pattern matching and optionally LangChain4J/GPT for dynamic validation
     * 
     * @param docText The document text to check
     * @param jurisdiction The jurisdiction to check against
     * @return List of compliance violations found
     */
    @Cacheable(value = "complianceRules", key = "#jurisdiction + '_' + #docText.hashCode()")
    public List<ComplianceViolation> checkCompliance(String docText, String jurisdiction) {
        logger.info("Starting compliance check for jurisdiction: {}", jurisdiction);
        
        List<ComplianceViolation> violations = new ArrayList<>();
        
        // Load active rules for jurisdiction using cached method
        List<ComplianceRule> rules = getActiveRulesForJurisdiction(jurisdiction);
        logger.debug("Loaded {} active rules for jurisdiction: {}", rules.size(), jurisdiction);
        
        // Check each rule
        for (ComplianceRule rule : rules) {
            try {
                List<ComplianceViolation> ruleViolations = checkRule(docText, rule);
                violations.addAll(ruleViolations);
            } catch (Exception e) {
                logger.error("Error checking rule {}: {}", rule.getRuleName(), e.getMessage(), e);
            }
        }
        
        // Optionally use LangChain4J for dynamic AI-based compliance checks
        // TODO: Implement LangChain4J integration for more sophisticated checks
        // violations.addAll(performAiComplianceCheck(docText, jurisdiction));
        
        auditLogger.info("COMPLIANCE_CHECK: Jurisdiction={}, ViolationsFound={}, Timestamp={}",
                jurisdiction, violations.size(), LocalDateTime.now());
        
        logger.info("Compliance check completed. Found {} violations", violations.size());
        return violations;
    }

    /**
     * Loads active compliance rules for a jurisdiction
     * Cached to avoid repeated database queries
     * 
     * @param jurisdiction The jurisdiction to load rules for
     * @return List of active compliance rules
     */
    @Cacheable(value = "complianceRules", key = "#jurisdiction")
    public List<ComplianceRule> getActiveRulesForJurisdiction(String jurisdiction) {
        logger.debug("Loading active rules for jurisdiction: {}", jurisdiction);
        return complianceRuleRepository.findByJurisdictionAndActive(jurisdiction, true);
    }

    /**
     * Evicts compliance rules cache when rules are updated
     * Called by ComplianceRuleLoaderService when rules are reloaded
     */
    @CacheEvict(value = "complianceRules", allEntries = true)
    public void evictComplianceRulesCache() {
        logger.info("Evicting compliance rules cache");
    }

    /**
     * Evicts specific jurisdiction cache entry
     * 
     * @param jurisdiction The jurisdiction to evict
     */
    @CacheEvict(value = "complianceRules", key = "#jurisdiction")
    public void evictJurisdictionCache(String jurisdiction) {
        logger.info("Evicting compliance rules cache for jurisdiction: {}", jurisdiction);
    }

    /**
     * Checks a single compliance rule against document text
     * 
     * @param docText The document text
     * @param rule The compliance rule to check
     * @return List of violations for this rule
     */
    private List<ComplianceViolation> checkRule(String docText, ComplianceRule rule) {
        List<ComplianceViolation> violations = new ArrayList<>();
        
        if (rule.getRegexPattern() == null || rule.getRegexPattern().isEmpty()) {
            return violations;
        }
        
        try {
            Pattern pattern = Pattern.compile(rule.getRegexPattern(), Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(docText);
            
            while (matcher.find()) {
                ComplianceViolation violation = new ComplianceViolation();
                violation.setRuleName(rule.getRuleName());
                violation.setJurisdiction(rule.getJurisdiction());
                violation.setDescription(rule.getDescription());
                violation.setSeverity(rule.getSeverity() != null ? rule.getSeverity() : "MEDIUM");
                violation.setMatchedText(matcher.group());
                violation.setPosition(matcher.start());
                violation.setTimestamp(LocalDateTime.now());
                
                violations.add(violation);
                
                logger.debug("Rule violation found: {} at position {}", rule.getRuleName(), matcher.start());
            }
        } catch (Exception e) {
            logger.error("Error applying regex pattern for rule {}: {}", rule.getRuleName(), e.getMessage());
        }
        
        return violations;
    }

    /**
     * Redacts Personally Identifiable Information (PII) from text
     * Uses regex patterns to detect and mask emails, SSNs, phone numbers, and credit cards
     * 
     * @param text The text to redact
     * @return Text with PII redacted
     */
    public String redactPII(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        logger.debug("Starting PII redaction");
        String redactedText = text;
        int redactionCount = 0;
        
        // Redact emails
        Matcher emailMatcher = EMAIL_PATTERN.matcher(redactedText);
        while (emailMatcher.find()) {
            redactionCount++;
        }
        redactedText = emailMatcher.replaceAll("[EMAIL_REDACTED]");
        
        // Redact SSNs
        Matcher ssnMatcher = SSN_PATTERN.matcher(redactedText);
        while (ssnMatcher.find()) {
            redactionCount++;
        }
        redactedText = ssnMatcher.replaceAll("[SSN_REDACTED]");
        
        // Redact phone numbers
        Matcher phoneMatcher = PHONE_PATTERN.matcher(redactedText);
        while (phoneMatcher.find()) {
            redactionCount++;
        }
        redactedText = phoneMatcher.replaceAll("[PHONE_REDACTED]");
        
        // Redact credit card numbers
        Matcher ccMatcher = CREDIT_CARD_PATTERN.matcher(redactedText);
        while (ccMatcher.find()) {
            redactionCount++;
        }
        redactedText = ccMatcher.replaceAll("[CC_REDACTED]");
        
        logger.info("PII redaction completed. {} items redacted", redactionCount);
        auditLogger.info("PII_REDACTION: ItemsRedacted={}, Timestamp={}", redactionCount, LocalDateTime.now());
        
        return redactedText;
    }

    /**
     * Performs comprehensive data protection scan to detect sensitive data
     * Detects PII, PHI (Protected Health Information), and other sensitive data
     * Encrypts/masks sensitive data and logs for audit
     * 
     * @param text The text to scan
     * @return DataProtectionReport containing scan results and protected text
     */
    public DataProtectionReport dataProtectionScan(String text) {
        logger.info("Starting data protection scan");
        
        DataProtectionReport report = new DataProtectionReport();
        report.setOriginalLength(text != null ? text.length() : 0);
        report.setScanTimestamp(LocalDateTime.now());
        
        if (text == null || text.isEmpty()) {
            report.setProtectedText("");
            return report;
        }
        
        List<SensitiveDataMatch> detectedData = new ArrayList<>();
        
        // Detect emails
        Matcher emailMatcher = EMAIL_PATTERN.matcher(text);
        while (emailMatcher.find()) {
            detectedData.add(new SensitiveDataMatch("EMAIL", emailMatcher.group(), emailMatcher.start()));
        }
        
        // Detect SSNs
        Matcher ssnMatcher = SSN_PATTERN.matcher(text);
        while (ssnMatcher.find()) {
            detectedData.add(new SensitiveDataMatch("SSN", ssnMatcher.group(), ssnMatcher.start()));
        }
        
        // Detect phone numbers
        Matcher phoneMatcher = PHONE_PATTERN.matcher(text);
        while (phoneMatcher.find()) {
            detectedData.add(new SensitiveDataMatch("PHONE", phoneMatcher.group(), phoneMatcher.start()));
        }
        
        // Detect credit cards
        Matcher ccMatcher = CREDIT_CARD_PATTERN.matcher(text);
        while (ccMatcher.find()) {
            detectedData.add(new SensitiveDataMatch("CREDIT_CARD", ccMatcher.group(), ccMatcher.start()));
        }
        
        // Detect PHI indicators (simplified - expand as needed)
        detectPHI(text, detectedData);
        
        report.setSensitiveDataMatches(detectedData);
        report.setSensitiveDataCount(detectedData.size());
        
        // Encrypt/mask sensitive data
        String protectedText = redactPII(text);
        report.setProtectedText(protectedText);
        report.setProtectedLength(protectedText.length());
        
        // Log for audit
        auditLogger.warn("DATA_PROTECTION_SCAN: SensitiveItemsFound={}, Types={}, Timestamp={}",
                detectedData.size(), 
                detectedData.stream().map(SensitiveDataMatch::getType).distinct().toArray(),
                LocalDateTime.now());
        
        logger.info("Data protection scan completed. Found {} sensitive items", detectedData.size());
        return report;
    }

    /**
     * Detects Protected Health Information (PHI) in text
     * Includes medical record numbers, patient identifiers, etc.
     * 
     * @param text The text to scan
     * @param detectedData List to add detected PHI to
     */
    private void detectPHI(String text, List<SensitiveDataMatch> detectedData) {
        // Medical record number pattern (simplified)
        Pattern mrnPattern = Pattern.compile("\\bMRN[:\\s]*\\d{6,10}\\b", Pattern.CASE_INSENSITIVE);
        Matcher mrnMatcher = mrnPattern.matcher(text);
        while (mrnMatcher.find()) {
            detectedData.add(new SensitiveDataMatch("MEDICAL_RECORD", mrnMatcher.group(), mrnMatcher.start()));
        }
        
        // Patient ID pattern
        Pattern patientIdPattern = Pattern.compile("\\bPATIENT[\\s_-]?ID[:\\s]*\\d{6,10}\\b", Pattern.CASE_INSENSITIVE);
        Matcher patientIdMatcher = patientIdPattern.matcher(text);
        while (patientIdMatcher.find()) {
            detectedData.add(new SensitiveDataMatch("PATIENT_ID", patientIdMatcher.group(), patientIdMatcher.start()));
        }
        
        // TODO: Add more PHI patterns (diagnosis codes, prescription info, etc.)
    }

    /**
     * TODO: Implement LangChain4J integration for AI-based compliance checks
     * Example implementation using GPT to validate complex compliance scenarios
     * 
     * @param docText The document text
     * @param jurisdiction The jurisdiction
     * @return List of AI-detected violations
     */
    private List<ComplianceViolation> performAiComplianceCheck(String docText, String jurisdiction) {
        // TODO: Implement with LangChain4J
        // Example:
        // ChatLanguageModel model = OpenAiChatModel.builder()
        //     .apiKey(openaiApiKey)
        //     .modelName("gpt-4")
        //     .build();
        // 
        // String prompt = String.format(
        //     "Analyze the following document for %s compliance violations:\n\n%s",
        //     jurisdiction, docText
        // );
        // 
        // String response = model.generate(prompt);
        // Parse response and return violations
        
        return new ArrayList<>();
    }

    /**
     * Inner class representing a compliance violation
     */
    public static class ComplianceViolation {
        private String ruleName;
        private String jurisdiction;
        private String description;
        private String severity;
        private String matchedText;
        private int position;
        private LocalDateTime timestamp;

        // Getters and Setters
        public String getRuleName() {
            return ruleName;
        }

        public void setRuleName(String ruleName) {
            this.ruleName = ruleName;
        }

        public String getJurisdiction() {
            return jurisdiction;
        }

        public void setJurisdiction(String jurisdiction) {
            this.jurisdiction = jurisdiction;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public String getMatchedText() {
            return matchedText;
        }

        public void setMatchedText(String matchedText) {
            this.matchedText = matchedText;
        }

        public int getPosition() {
            return position;
        }

        public void setPosition(int position) {
            this.position = position;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
        }
    }

    /**
     * Inner class representing a sensitive data match
     */
    public static class SensitiveDataMatch {
        private String type;
        private String value;
        private int position;

        public SensitiveDataMatch(String type, String value, int position) {
            this.type = type;
            this.value = value;
            this.position = position;
        }

        public String getType() {
            return type;
        }

        public String getValue() {
            return value;
        }

        public int getPosition() {
            return position;
        }
    }

    /**
     * Inner class representing data protection scan report
     */
    public static class DataProtectionReport {
        private int originalLength;
        private int protectedLength;
        private int sensitiveDataCount;
        private List<SensitiveDataMatch> sensitiveDataMatches;
        private String protectedText;
        private LocalDateTime scanTimestamp;

        // Getters and Setters
        public int getOriginalLength() {
            return originalLength;
        }

        public void setOriginalLength(int originalLength) {
            this.originalLength = originalLength;
        }

        public int getProtectedLength() {
            return protectedLength;
        }

        public void setProtectedLength(int protectedLength) {
            this.protectedLength = protectedLength;
        }

        public int getSensitiveDataCount() {
            return sensitiveDataCount;
        }

        public void setSensitiveDataCount(int sensitiveDataCount) {
            this.sensitiveDataCount = sensitiveDataCount;
        }

        public List<SensitiveDataMatch> getSensitiveDataMatches() {
            return sensitiveDataMatches;
        }

        public void setSensitiveDataMatches(List<SensitiveDataMatch> sensitiveDataMatches) {
            this.sensitiveDataMatches = sensitiveDataMatches;
        }

        public String getProtectedText() {
            return protectedText;
        }

        public void setProtectedText(String protectedText) {
            this.protectedText = protectedText;
        }

        public LocalDateTime getScanTimestamp() {
            return scanTimestamp;
        }

        public void setScanTimestamp(LocalDateTime scanTimestamp) {
            this.scanTimestamp = scanTimestamp;
        }
    }
}

