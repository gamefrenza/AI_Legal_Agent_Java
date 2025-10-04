package com.legalai.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalai.agent.entity.ComplianceRule;
import com.legalai.agent.repository.ComplianceRuleRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for dynamically loading compliance rules from JSON configuration
 * Supports multiple jurisdictions including CCPA, UK GDPR, HIPAA, etc.
 */
@Service
public class ComplianceRuleLoaderService {

    private static final Logger logger = LoggerFactory.getLogger(ComplianceRuleLoaderService.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    @Autowired
    private ComplianceRuleRepository complianceRuleRepository;

    @Autowired
    private ComplianceEngineService complianceEngineService;

    @Autowired
    private ResourceLoader resourceLoader;

    @Value("${compliance.rules.file:classpath:compliance-rules.json}")
    private String rulesFilePath;

    @Value("${compliance.rules.autoload:true}")
    private boolean autoload;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Automatically load compliance rules on application startup
     */
    @PostConstruct
    public void init() {
        if (autoload) {
            logger.info("Auto-loading compliance rules from: {}", rulesFilePath);
            try {
                loadRulesFromJson();
            } catch (Exception e) {
                logger.error("Failed to auto-load compliance rules: {}", e.getMessage(), e);
                // Don't fail startup, just log the error
            }
        } else {
            logger.info("Auto-load disabled for compliance rules");
        }
    }

    /**
     * Load compliance rules from JSON file
     * Creates or updates rules in the database
     * 
     * @return Number of rules loaded
     * @throws IOException if file cannot be read
     */
    @Transactional
    public int loadRulesFromJson() throws IOException {
        logger.info("Loading compliance rules from JSON: {}", rulesFilePath);
        
        Resource resource = resourceLoader.getResource(rulesFilePath);
        
        if (!resource.exists()) {
            logger.warn("Compliance rules file not found: {}", rulesFilePath);
            return 0;
        }
        
        List<ComplianceRule> loadedRules = new ArrayList<>();
        
        try (InputStream inputStream = resource.getInputStream()) {
            JsonNode rootNode = objectMapper.readTree(inputStream);
            
            // Parse metadata
            JsonNode metadataNode = rootNode.get("metadata");
            if (metadataNode != null) {
                String version = metadataNode.path("version").asText();
                String lastUpdated = metadataNode.path("lastUpdated").asText();
                logger.info("Loading rules version: {}, last updated: {}", version, lastUpdated);
            }
            
            // Parse rules array
            JsonNode rulesNode = rootNode.get("rules");
            if (rulesNode == null || !rulesNode.isArray()) {
                logger.warn("No rules array found in JSON file");
                return 0;
            }
            
            for (JsonNode ruleNode : rulesNode) {
                try {
                    ComplianceRule rule = parseRuleFromJson(ruleNode);
                    
                    // Check if rule already exists
                    List<ComplianceRule> existingRules = complianceRuleRepository
                            .findByRuleNameAndJurisdiction(rule.getRuleName(), rule.getJurisdiction());
                    
                    if (!existingRules.isEmpty()) {
                        // Update existing rule
                        ComplianceRule existingRule = existingRules.get(0);
                        existingRule.setDescription(rule.getDescription());
                        existingRule.setRegexPattern(rule.getRegexPattern());
                        existingRule.setSeverity(rule.getSeverity());
                        existingRule.setActive(rule.getActive());
                        
                        complianceRuleRepository.save(existingRule);
                        logger.debug("Updated existing rule: {} for jurisdiction: {}", 
                                rule.getRuleName(), rule.getJurisdiction());
                    } else {
                        // Create new rule
                        complianceRuleRepository.save(rule);
                        logger.debug("Created new rule: {} for jurisdiction: {}", 
                                rule.getRuleName(), rule.getJurisdiction());
                    }
                    
                    loadedRules.add(rule);
                    
                } catch (Exception e) {
                    logger.error("Failed to parse rule: {}", ruleNode, e);
                    // Continue loading other rules
                }
            }
        }
        
        int loadedCount = loadedRules.size();
        logger.info("Successfully loaded {} compliance rules", loadedCount);
        
        // Log by jurisdiction
        loadedRules.stream()
                .map(ComplianceRule::getJurisdiction)
                .distinct()
                .forEach(jurisdiction -> {
                    long count = loadedRules.stream()
                            .filter(r -> jurisdiction.equals(r.getJurisdiction()))
                            .count();
                    logger.info("  - {}: {} rules", jurisdiction, count);
                });
        
        auditLogger.info("COMPLIANCE_RULES_LOADED: Count={}, File={}", loadedCount, rulesFilePath);
        
        // Evict compliance rules cache after loading new rules
        complianceEngineService.evictComplianceRulesCache();
        
        return loadedCount;
    }

    /**
     * Parse a single compliance rule from JSON node
     * 
     * @param ruleNode JSON node containing rule data
     * @return ComplianceRule entity
     */
    private ComplianceRule parseRuleFromJson(JsonNode ruleNode) {
        ComplianceRule rule = new ComplianceRule();
        
        rule.setJurisdiction(ruleNode.path("jurisdiction").asText());
        rule.setRuleName(ruleNode.path("ruleName").asText());
        rule.setDescription(ruleNode.path("description").asText());
        rule.setRegexPattern(ruleNode.path("regexPattern").asText());
        rule.setSeverity(ruleNode.path("severity").asText("MEDIUM"));
        rule.setActive(ruleNode.path("active").asBoolean(true));
        
        return rule;
    }

    /**
     * Reload rules from JSON file
     * Can be called via admin endpoint to refresh rules without restart
     * 
     * @return Number of rules loaded
     * @throws IOException if file cannot be read
     */
    @Transactional
    public int reloadRules() throws IOException {
        logger.info("Manually reloading compliance rules");
        return loadRulesFromJson();
    }

    /**
     * Load rules from custom JSON string
     * Useful for testing or dynamic rule updates
     * 
     * @param jsonContent JSON string containing rules
     * @return Number of rules loaded
     * @throws IOException if JSON cannot be parsed
     */
    @Transactional
    public int loadRulesFromString(String jsonContent) throws IOException {
        logger.info("Loading compliance rules from string");
        
        List<ComplianceRule> loadedRules = new ArrayList<>();
        
        JsonNode rootNode = objectMapper.readTree(jsonContent);
        JsonNode rulesNode = rootNode.get("rules");
        
        if (rulesNode == null || !rulesNode.isArray()) {
            logger.warn("No rules array found in JSON string");
            return 0;
        }
        
        for (JsonNode ruleNode : rulesNode) {
            try {
                ComplianceRule rule = parseRuleFromJson(ruleNode);
                
                List<ComplianceRule> existingRules = complianceRuleRepository
                        .findByRuleNameAndJurisdiction(rule.getRuleName(), rule.getJurisdiction());
                
                if (!existingRules.isEmpty()) {
                    ComplianceRule existingRule = existingRules.get(0);
                    existingRule.setDescription(rule.getDescription());
                    existingRule.setRegexPattern(rule.getRegexPattern());
                    existingRule.setSeverity(rule.getSeverity());
                    existingRule.setActive(rule.getActive());
                    complianceRuleRepository.save(existingRule);
                } else {
                    complianceRuleRepository.save(rule);
                }
                
                loadedRules.add(rule);
                
            } catch (Exception e) {
                logger.error("Failed to parse rule from string: {}", ruleNode, e);
            }
        }
        
        logger.info("Loaded {} rules from string", loadedRules.size());
        
        // Evict compliance rules cache after loading new rules
        complianceEngineService.evictComplianceRulesCache();
        
        return loadedRules.size();
    }

    /**
     * Get statistics about loaded rules
     * 
     * @return Statistics map with jurisdiction counts
     */
    public RuleStatistics getRuleStatistics() {
        List<ComplianceRule> allRules = (List<ComplianceRule>) complianceRuleRepository.findAll();
        
        RuleStatistics stats = new RuleStatistics();
        stats.setTotalRules(allRules.size());
        stats.setActiveRules((int) allRules.stream().filter(ComplianceRule::getActive).count());
        stats.setInactiveRules((int) allRules.stream().filter(r -> !r.getActive()).count());
        
        // Count by jurisdiction
        allRules.stream()
                .map(ComplianceRule::getJurisdiction)
                .distinct()
                .forEach(jurisdiction -> {
                    long count = allRules.stream()
                            .filter(r -> jurisdiction.equals(r.getJurisdiction()))
                            .count();
                    stats.addJurisdictionCount(jurisdiction, (int) count);
                });
        
        // Count by severity
        allRules.stream()
                .map(ComplianceRule::getSeverity)
                .distinct()
                .forEach(severity -> {
                    long count = allRules.stream()
                            .filter(r -> severity != null && severity.equals(r.getSeverity()))
                            .count();
                    stats.addSeverityCount(severity != null ? severity : "UNSET", (int) count);
                });
        
        return stats;
    }

    /**
     * Inner class for rule statistics
     */
    public static class RuleStatistics {
        private int totalRules;
        private int activeRules;
        private int inactiveRules;
        private java.util.Map<String, Integer> byJurisdiction = new java.util.HashMap<>();
        private java.util.Map<String, Integer> bySeverity = new java.util.HashMap<>();

        // Getters and Setters
        public int getTotalRules() { return totalRules; }
        public void setTotalRules(int totalRules) { this.totalRules = totalRules; }
        
        public int getActiveRules() { return activeRules; }
        public void setActiveRules(int activeRules) { this.activeRules = activeRules; }
        
        public int getInactiveRules() { return inactiveRules; }
        public void setInactiveRules(int inactiveRules) { this.inactiveRules = inactiveRules; }
        
        public java.util.Map<String, Integer> getByJurisdiction() { return byJurisdiction; }
        public void setByJurisdiction(java.util.Map<String, Integer> byJurisdiction) { 
            this.byJurisdiction = byJurisdiction; 
        }
        
        public java.util.Map<String, Integer> getBySeverity() { return bySeverity; }
        public void setBySeverity(java.util.Map<String, Integer> bySeverity) { 
            this.bySeverity = bySeverity; 
        }
        
        public void addJurisdictionCount(String jurisdiction, int count) {
            byJurisdiction.put(jurisdiction, count);
        }
        
        public void addSeverityCount(String severity, int count) {
            bySeverity.put(severity, count);
        }
    }
}

