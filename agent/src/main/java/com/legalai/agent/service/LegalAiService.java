package com.legalai.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Legal AI Service using LangChain4J and OpenAI for intelligent legal analysis
 * Provides contract analysis, legal research, risk assessment, and AI-powered compliance validation
 */
@Service
public class LegalAiService {

    private static final Logger logger = LoggerFactory.getLogger(LegalAiService.class);
    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");

    @Value("${openai.api-key}")
    private String openaiApiKey;

    @Autowired
    private ComplianceEngineService complianceEngineService;

    private ChatLanguageModel chatModel;
    private ChatMemory chatMemory;
    private Map<String, String> jurisdictionPrompts;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Initializes the LangChain4J OpenAI chat model and loads jurisdiction-specific prompts
     */
    @PostConstruct
    public void init() {
        logger.info("Initializing LegalAiService with OpenAI GPT-4o");
        
        try {
            // Initialize OpenAI Chat Model with GPT-4o and temperature 0 for consistent responses
            chatModel = OpenAiChatModel.builder()
                    .apiKey(openaiApiKey)
                    .modelName("gpt-4o")
                    .temperature(0.0)
                    .timeout(Duration.ofSeconds(60))
                    .logRequests(true)
                    .logResponses(false)
                    .build();
            
            // Initialize in-memory chat memory for conversational context
            chatMemory = MessageWindowChatMemory.withMaxMessages(20);
            
            logger.info("OpenAI Chat Model initialized successfully");
            
            // Load jurisdiction-specific prompts
            loadJurisdictionPrompts();
            
            auditLogger.info("LEGAL_AI_INITIALIZED: Model=gpt-4o, Temperature=0.0");
            
        } catch (Exception e) {
            logger.error("Failed to initialize LegalAiService: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Legal AI Service", e);
        }
    }

    /**
     * Loads jurisdiction-specific system prompts for legal analysis
     */
    private void loadJurisdictionPrompts() {
        logger.info("Loading jurisdiction-specific prompts");
        
        jurisdictionPrompts = new HashMap<>();
        
        // US jurisdiction prompts
        jurisdictionPrompts.put("US", 
            "You are a legal expert specializing in United States law. " +
            "Provide analysis based on federal and state laws, citing relevant statutes and case law where applicable.");
        
        jurisdictionPrompts.put("US-CA", 
            "You are a legal expert specializing in California state law. " +
            "Analyze contracts under California Civil Code and relevant case precedents.");
        
        jurisdictionPrompts.put("US-NY", 
            "You are a legal expert specializing in New York state law. " +
            "Apply New York contract law and UCC provisions.");
        
        // EU jurisdiction prompts
        jurisdictionPrompts.put("EU", 
            "You are a legal expert specializing in European Union law. " +
            "Analyze compliance with EU directives and regulations, especially GDPR.");
        
        // UK jurisdiction prompt
        jurisdictionPrompts.put("UK", 
            "You are a legal expert specializing in United Kingdom law. " +
            "Analyze under UK common law and statutory provisions.");
        
        // Default prompt
        jurisdictionPrompts.put("DEFAULT", 
            "You are a legal expert providing general legal analysis. " +
            "Identify potential issues and provide recommendations based on common legal principles.");
        
        logger.info("Loaded {} jurisdiction-specific prompts", jurisdictionPrompts.size());
    }

    /**
     * Analyzes a contract under specified jurisdiction law
     * Flags ambiguities, identifies risks, and suggests edits
     * 
     * @param docText The contract text to analyze
     * @param jurisdiction The jurisdiction to analyze under (e.g., "US-CA", "EU")
     * @return ContractAnalysisResult with risks, ambiguities, and suggestions
     */
    @Async
    @Cacheable(value = "aiAnalysisResults", key = "#docText.hashCode() + '_' + #jurisdiction")
    public CompletableFuture<ContractAnalysisResult> analyzeContract(String docText, String jurisdiction) {
        logger.info("Starting contract analysis for jurisdiction: {}", jurisdiction);
        
        try {
            // Get jurisdiction-specific system prompt
            String systemPrompt = jurisdictionPrompts.getOrDefault(jurisdiction, 
                    jurisdictionPrompts.get("DEFAULT"));
            
            // Build analysis prompt
            String userPrompt = String.format(
                "Analyze the following contract under %s law. " +
                "Please provide a structured analysis with:\n" +
                "1. Key ambiguities that need clarification\n" +
                "2. Legal risks and potential liabilities\n" +
                "3. Specific edits or additions recommended\n" +
                "4. Overall risk summary\n\n" +
                "Format your response as JSON with the following structure:\n" +
                "{\n" +
                "  \"summary\": \"Brief overall assessment\",\n" +
                "  \"ambiguities\": [\"list of ambiguous clauses\"],\n" +
                "  \"risks\": [{\"description\": \"risk description\", \"severity\": \"HIGH|MEDIUM|LOW\"}],\n" +
                "  \"suggestions\": [\"list of recommended edits\"],\n" +
                "  \"overallRiskLevel\": \"HIGH|MEDIUM|LOW\"\n" +
                "}\n\n" +
                "Contract text:\n%s",
                jurisdiction, docText
            );
            
            // Execute chain with memory
            chatMemory.add(new SystemMessage(systemPrompt));
            chatMemory.add(new UserMessage(userPrompt));
            
            String response = chatModel.generate(chatMemory.messages()).content().text();
            
            chatMemory.add(new AiMessage(response));
            
            // Parse JSON response
            ContractAnalysisResult result = parseContractAnalysis(response, jurisdiction);
            
            auditLogger.info("CONTRACT_ANALYZED: Jurisdiction={}, Risks={}, Ambiguities={}, Suggestions={}",
                    jurisdiction, result.getRisks().size(), result.getAmbiguities().size(), 
                    result.getSuggestions().size());
            
            logger.info("Contract analysis completed successfully");
            return CompletableFuture.completedFuture(result);
            
        } catch (Exception e) {
            logger.error("Failed to analyze contract: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(new RuntimeException("Contract analysis failed", e));
        }
    }

    /**
     * Conducts legal research on a specific topic within a jurisdiction
     * Provides analysis with source citations
     * 
     * @param query The legal research query
     * @param jurisdiction The jurisdiction to research in
     * @return LegalResearchResult with findings and citations
     */
    @Async
    @Cacheable(value = "aiAnalysisResults", key = "#query.hashCode() + '_' + #jurisdiction")
    public CompletableFuture<LegalResearchResult> researchTopic(String query, String jurisdiction) {
        logger.info("Starting legal research: {} in jurisdiction: {}", query, jurisdiction);
        
        try {
            String systemPrompt = jurisdictionPrompts.getOrDefault(jurisdiction, 
                    jurisdictionPrompts.get("DEFAULT"));
            
            String userPrompt = String.format(
                "Conduct comprehensive legal research on the following topic in %s:\n\n" +
                "Research Query: %s\n\n" +
                "Please provide:\n" +
                "1. Summary of relevant legal principles\n" +
                "2. Key statutes and regulations\n" +
                "3. Notable case law and precedents\n" +
                "4. Practical implications and recommendations\n" +
                "5. Citations and sources\n\n" +
                "Format your response as JSON:\n" +
                "{\n" +
                "  \"summary\": \"Overview of research findings\",\n" +
                "  \"statutes\": [\"list of relevant statutes\"],\n" +
                "  \"cases\": [{\"name\": \"case name\", \"citation\": \"citation\", \"relevance\": \"why relevant\"}],\n" +
                "  \"principles\": [\"list of legal principles\"],\n" +
                "  \"recommendations\": [\"practical recommendations\"],\n" +
                "  \"sources\": [\"list of additional sources\"]\n" +
                "}",
                jurisdiction, query
            );
            
            chatMemory.add(new SystemMessage(systemPrompt));
            chatMemory.add(new UserMessage(userPrompt));
            
            String response = chatModel.generate(chatMemory.messages()).content().text();
            
            chatMemory.add(new AiMessage(response));
            
            // Parse JSON response
            LegalResearchResult result = parseLegalResearch(response, query, jurisdiction);
            
            // TODO: Integrate with vector search for enhanced retrieval
            // Future implementation:
            // 1. Use Elasticsearch for semantic search of legal documents
            // 2. Store embeddings of legal texts for similarity search
            // 3. Retrieve relevant case law and statutes from vector database
            // 4. Combine with LLM analysis for comprehensive research
            // 
            // Example integration:
            // ElasticsearchClient client = ...;
            // SearchResponse<LegalDocument> searchResult = client.search(s -> s
            //     .index("legal-documents")
            //     .query(q -> q.knn(k -> k
            //         .field("embedding")
            //         .queryVector(embedding)
            //         .k(10)
            //     )),
            //     LegalDocument.class
            // );
            
            auditLogger.info("LEGAL_RESEARCH: Query={}, Jurisdiction={}, Sources={}",
                    query, jurisdiction, result.getSources().size());
            
            logger.info("Legal research completed successfully");
            return CompletableFuture.completedFuture(result);
            
        } catch (Exception e) {
            logger.error("Failed to conduct legal research: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(new RuntimeException("Legal research failed", e));
        }
    }

    /**
     * Performs comprehensive risk assessment on a document
     * Scores risks (0-10) across multiple categories
     * 
     * @param docText The document text to assess
     * @return RiskAssessmentResult with categorized risk scores
     */
    @Async
    @Cacheable(value = "aiAnalysisResults", key = "#docText.hashCode()")
    public CompletableFuture<RiskAssessmentResult> riskAssessment(String docText) {
        logger.info("Starting risk assessment");
        
        try {
            String systemPrompt = "You are a legal risk assessment expert. " +
                    "Analyze documents for potential legal risks and provide quantitative scores.";
            
            String userPrompt = String.format(
                "Assess the legal risks in the following document. " +
                "Provide risk scores (0-10, where 10 is highest risk) for:\n" +
                "1. Liability exposure\n" +
                "2. Non-compete clause enforceability concerns\n" +
                "3. Intellectual property risks\n" +
                "4. Confidentiality and data protection risks\n" +
                "5. Termination and dispute resolution risks\n" +
                "6. Regulatory compliance risks\n" +
                "7. Financial liability risks\n" +
                "8. Indemnification risks\n\n" +
                "Format response as JSON:\n" +
                "{\n" +
                "  \"overallRiskScore\": 0-10,\n" +
                "  \"riskCategories\": [\n" +
                "    {\"category\": \"category name\", \"score\": 0-10, \"details\": \"explanation\"}\n" +
                "  ],\n" +
                "  \"criticalIssues\": [\"list of critical issues\"],\n" +
                "  \"recommendations\": [\"list of recommendations\"],\n" +
                "  \"summary\": \"overall risk assessment summary\"\n" +
                "}\n\n" +
                "Document:\n%s",
                docText
            );
            
            chatMemory.add(new SystemMessage(systemPrompt));
            chatMemory.add(new UserMessage(userPrompt));
            
            String response = chatModel.generate(chatMemory.messages()).content().text();
            
            chatMemory.add(new AiMessage(response));
            
            // Parse JSON response
            RiskAssessmentResult result = parseRiskAssessment(response);
            
            // Combine with rule-based compliance checks
            // This enhances AI assessment with deterministic rule checks
            try {
                ComplianceEngineService.DataProtectionReport protectionReport = 
                        complianceEngineService.dataProtectionScan(docText);
                
                if (protectionReport.getSensitiveDataCount() > 0) {
                    RiskCategory dataRisk = new RiskCategory();
                    dataRisk.setCategory("Data Protection");
                    dataRisk.setScore(Math.min(10, protectionReport.getSensitiveDataCount()));
                    dataRisk.setDetails(String.format("Detected %d sensitive data items: %s", 
                            protectionReport.getSensitiveDataCount(),
                            protectionReport.getSensitiveDataMatches().stream()
                                .map(ComplianceEngineService.SensitiveDataMatch::getType)
                                .distinct()
                                .collect(Collectors.joining(", "))));
                    
                    result.getRiskCategories().add(dataRisk);
                    
                    // Adjust overall risk score
                    result.setOverallRiskScore(Math.max(result.getOverallRiskScore(), 
                            protectionReport.getSensitiveDataCount()));
                }
            } catch (Exception e) {
                logger.warn("Failed to combine with compliance checks: {}", e.getMessage());
            }
            
            auditLogger.info("RISK_ASSESSMENT: OverallScore={}, Categories={}, CriticalIssues={}",
                    result.getOverallRiskScore(), result.getRiskCategories().size(), 
                    result.getCriticalIssues().size());
            
            logger.info("Risk assessment completed successfully");
            return CompletableFuture.completedFuture(result);
            
        } catch (Exception e) {
            logger.error("Failed to perform risk assessment: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(new RuntimeException("Risk assessment failed", e));
        }
    }

    /**
     * Validates compliance using AI-powered analysis
     * Cross-checks against jurisdiction rules and regulations
     * Merges with rule-based compliance engine for comprehensive validation
     * 
     * @param docText The document text to validate
     * @param jurisdiction The jurisdiction to validate against
     * @return ComplianceValidationResult with passes and fails
     */
    @Async
    @Cacheable(value = "aiAnalysisResults", key = "#docText.hashCode() + '_' + #jurisdiction")
    public CompletableFuture<ComplianceValidationResult> validateComplianceAi(String docText, String jurisdiction) {
        logger.info("Starting AI-powered compliance validation for jurisdiction: {}", jurisdiction);
        
        try {
            // First, run rule-based compliance checks
            List<ComplianceEngineService.ComplianceViolation> ruleBasedViolations = 
                    complianceEngineService.checkCompliance(docText, jurisdiction);
            
            // Build rules context for AI
            String rulesContext = buildRulesContext(jurisdiction);
            
            String systemPrompt = jurisdictionPrompts.getOrDefault(jurisdiction, 
                    jurisdictionPrompts.get("DEFAULT"));
            
            String userPrompt = String.format(
                "Validate the following document for compliance with %s regulations.\n\n" +
                "Relevant Rules and Regulations:\n%s\n\n" +
                "Analyze the document and provide:\n" +
                "1. List of compliance requirements that PASS\n" +
                "2. List of compliance requirements that FAIL\n" +
                "3. Areas of concern requiring review\n" +
                "4. Recommendations for achieving compliance\n\n" +
                "Format response as JSON:\n" +
                "{\n" +
                "  \"overallCompliant\": true/false,\n" +
                "  \"passes\": [{\"requirement\": \"requirement name\", \"details\": \"why it passes\"}],\n" +
                "  \"fails\": [{\"requirement\": \"requirement name\", \"severity\": \"HIGH|MEDIUM|LOW\", \"details\": \"why it fails\", \"recommendation\": \"how to fix\"}],\n" +
                "  \"concernAreas\": [\"list of areas needing review\"],\n" +
                "  \"recommendations\": [\"general recommendations\"],\n" +
                "  \"summary\": \"overall compliance summary\"\n" +
                "}\n\n" +
                "Document:\n%s",
                jurisdiction, rulesContext, docText
            );
            
            chatMemory.add(new SystemMessage(systemPrompt));
            chatMemory.add(new UserMessage(userPrompt));
            
            String response = chatModel.generate(chatMemory.messages()).content().text();
            
            chatMemory.add(new AiMessage(response));
            
            // Parse JSON response
            ComplianceValidationResult result = parseComplianceValidation(response, jurisdiction);
            
            // Merge with rule-based violations
            for (ComplianceEngineService.ComplianceViolation violation : ruleBasedViolations) {
                ComplianceFail fail = new ComplianceFail();
                fail.setRequirement(violation.getRuleName());
                fail.setSeverity(violation.getSeverity());
                fail.setDetails(violation.getDescription() + " - Matched: " + violation.getMatchedText());
                fail.setRecommendation("Review and remediate this rule violation");
                
                result.getFails().add(fail);
                result.setOverallCompliant(false);
            }
            
            auditLogger.info("COMPLIANCE_VALIDATION_AI: Jurisdiction={}, Passes={}, Fails={}, RuleViolations={}",
                    jurisdiction, result.getPasses().size(), result.getFails().size(), 
                    ruleBasedViolations.size());
            
            logger.info("AI compliance validation completed successfully");
            return CompletableFuture.completedFuture(result);
            
        } catch (Exception e) {
            logger.error("Failed to validate compliance with AI: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(new RuntimeException("AI compliance validation failed", e));
        }
    }

    /**
     * Builds context of rules and regulations for a jurisdiction
     * Cached to avoid repeated database queries
     */
    @Cacheable(value = "jurisdictionPrompts", key = "#jurisdiction")
    private String buildRulesContext(String jurisdiction) {
        StringBuilder context = new StringBuilder();
        
        switch (jurisdiction) {
            case "EU":
                context.append("- GDPR: Data protection and privacy\n");
                context.append("- Consumer Rights Directive\n");
                context.append("- ePrivacy Directive\n");
                break;
            case "US-CA":
                context.append("- CCPA: California Consumer Privacy Act\n");
                context.append("- California Civil Code requirements\n");
                context.append("- Labor Code provisions\n");
                break;
            case "US":
                context.append("- Federal contract law\n");
                context.append("- UCC (Uniform Commercial Code)\n");
                context.append("- Consumer protection laws\n");
                break;
            default:
                context.append("- General contract law principles\n");
                context.append("- Consumer protection requirements\n");
                context.append("- Data protection standards\n");
        }
        
        return context.toString();
    }

    // JSON parsing methods

    private ContractAnalysisResult parseContractAnalysis(String jsonResponse, String jurisdiction) {
        try {
            // Extract JSON from response (may be wrapped in markdown code blocks)
            String cleanJson = extractJson(jsonResponse);
            JsonNode node = objectMapper.readTree(cleanJson);
            
            ContractAnalysisResult result = new ContractAnalysisResult();
            result.setJurisdiction(jurisdiction);
            result.setSummary(node.path("summary").asText());
            result.setOverallRiskLevel(node.path("overallRiskLevel").asText("MEDIUM"));
            
            // Parse ambiguities
            List<String> ambiguities = new ArrayList<>();
            node.path("ambiguities").forEach(n -> ambiguities.add(n.asText()));
            result.setAmbiguities(ambiguities);
            
            // Parse risks
            List<ContractRisk> risks = new ArrayList<>();
            node.path("risks").forEach(n -> {
                ContractRisk risk = new ContractRisk();
                risk.setDescription(n.path("description").asText());
                risk.setSeverity(n.path("severity").asText("MEDIUM"));
                risks.add(risk);
            });
            result.setRisks(risks);
            
            // Parse suggestions
            List<String> suggestions = new ArrayList<>();
            node.path("suggestions").forEach(n -> suggestions.add(n.asText()));
            result.setSuggestions(suggestions);
            
            return result;
        } catch (Exception e) {
            logger.error("Failed to parse contract analysis JSON: {}", e.getMessage());
            return createFallbackContractAnalysis(jsonResponse, jurisdiction);
        }
    }

    private LegalResearchResult parseLegalResearch(String jsonResponse, String query, String jurisdiction) {
        try {
            String cleanJson = extractJson(jsonResponse);
            JsonNode node = objectMapper.readTree(cleanJson);
            
            LegalResearchResult result = new LegalResearchResult();
            result.setQuery(query);
            result.setJurisdiction(jurisdiction);
            result.setSummary(node.path("summary").asText());
            
            // Parse statutes
            List<String> statutes = new ArrayList<>();
            node.path("statutes").forEach(n -> statutes.add(n.asText()));
            result.setStatutes(statutes);
            
            // Parse cases
            List<LegalCase> cases = new ArrayList<>();
            node.path("cases").forEach(n -> {
                LegalCase legalCase = new LegalCase();
                legalCase.setName(n.path("name").asText());
                legalCase.setCitation(n.path("citation").asText());
                legalCase.setRelevance(n.path("relevance").asText());
                cases.add(legalCase);
            });
            result.setCases(cases);
            
            // Parse principles
            List<String> principles = new ArrayList<>();
            node.path("principles").forEach(n -> principles.add(n.asText()));
            result.setPrinciples(principles);
            
            // Parse recommendations
            List<String> recommendations = new ArrayList<>();
            node.path("recommendations").forEach(n -> recommendations.add(n.asText()));
            result.setRecommendations(recommendations);
            
            // Parse sources
            List<String> sources = new ArrayList<>();
            node.path("sources").forEach(n -> sources.add(n.asText()));
            result.setSources(sources);
            
            return result;
        } catch (Exception e) {
            logger.error("Failed to parse legal research JSON: {}", e.getMessage());
            return createFallbackResearchResult(jsonResponse, query, jurisdiction);
        }
    }

    private RiskAssessmentResult parseRiskAssessment(String jsonResponse) {
        try {
            String cleanJson = extractJson(jsonResponse);
            JsonNode node = objectMapper.readTree(cleanJson);
            
            RiskAssessmentResult result = new RiskAssessmentResult();
            result.setOverallRiskScore(node.path("overallRiskScore").asInt(5));
            result.setSummary(node.path("summary").asText());
            
            // Parse risk categories
            List<RiskCategory> categories = new ArrayList<>();
            node.path("riskCategories").forEach(n -> {
                RiskCategory category = new RiskCategory();
                category.setCategory(n.path("category").asText());
                category.setScore(n.path("score").asInt(0));
                category.setDetails(n.path("details").asText());
                categories.add(category);
            });
            result.setRiskCategories(categories);
            
            // Parse critical issues
            List<String> criticalIssues = new ArrayList<>();
            node.path("criticalIssues").forEach(n -> criticalIssues.add(n.asText()));
            result.setCriticalIssues(criticalIssues);
            
            // Parse recommendations
            List<String> recommendations = new ArrayList<>();
            node.path("recommendations").forEach(n -> recommendations.add(n.asText()));
            result.setRecommendations(recommendations);
            
            return result;
        } catch (Exception e) {
            logger.error("Failed to parse risk assessment JSON: {}", e.getMessage());
            return createFallbackRiskAssessment(jsonResponse);
        }
    }

    private ComplianceValidationResult parseComplianceValidation(String jsonResponse, String jurisdiction) {
        try {
            String cleanJson = extractJson(jsonResponse);
            JsonNode node = objectMapper.readTree(cleanJson);
            
            ComplianceValidationResult result = new ComplianceValidationResult();
            result.setJurisdiction(jurisdiction);
            result.setOverallCompliant(node.path("overallCompliant").asBoolean(false));
            result.setSummary(node.path("summary").asText());
            
            // Parse passes
            List<CompliancePass> passes = new ArrayList<>();
            node.path("passes").forEach(n -> {
                CompliancePass pass = new CompliancePass();
                pass.setRequirement(n.path("requirement").asText());
                pass.setDetails(n.path("details").asText());
                passes.add(pass);
            });
            result.setPasses(passes);
            
            // Parse fails
            List<ComplianceFail> fails = new ArrayList<>();
            node.path("fails").forEach(n -> {
                ComplianceFail fail = new ComplianceFail();
                fail.setRequirement(n.path("requirement").asText());
                fail.setSeverity(n.path("severity").asText("MEDIUM"));
                fail.setDetails(n.path("details").asText());
                fail.setRecommendation(n.path("recommendation").asText());
                fails.add(fail);
            });
            result.setFails(fails);
            
            // Parse concern areas
            List<String> concernAreas = new ArrayList<>();
            node.path("concernAreas").forEach(n -> concernAreas.add(n.asText()));
            result.setConcernAreas(concernAreas);
            
            // Parse recommendations
            List<String> recommendations = new ArrayList<>();
            node.path("recommendations").forEach(n -> recommendations.add(n.asText()));
            result.setRecommendations(recommendations);
            
            return result;
        } catch (Exception e) {
            logger.error("Failed to parse compliance validation JSON: {}", e.getMessage());
            return createFallbackComplianceValidation(jsonResponse, jurisdiction);
        }
    }

    /**
     * Extracts JSON from response that may be wrapped in markdown code blocks
     */
    private String extractJson(String response) {
        // Remove markdown code blocks if present
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    // Fallback methods for when JSON parsing fails

    private ContractAnalysisResult createFallbackContractAnalysis(String response, String jurisdiction) {
        ContractAnalysisResult result = new ContractAnalysisResult();
        result.setJurisdiction(jurisdiction);
        result.setSummary(response);
        result.setOverallRiskLevel("MEDIUM");
        result.setAmbiguities(new ArrayList<>());
        result.setRisks(new ArrayList<>());
        result.setSuggestions(new ArrayList<>());
        return result;
    }

    private LegalResearchResult createFallbackResearchResult(String response, String query, String jurisdiction) {
        LegalResearchResult result = new LegalResearchResult();
        result.setQuery(query);
        result.setJurisdiction(jurisdiction);
        result.setSummary(response);
        result.setStatutes(new ArrayList<>());
        result.setCases(new ArrayList<>());
        result.setPrinciples(new ArrayList<>());
        result.setRecommendations(new ArrayList<>());
        result.setSources(new ArrayList<>());
        return result;
    }

    private RiskAssessmentResult createFallbackRiskAssessment(String response) {
        RiskAssessmentResult result = new RiskAssessmentResult();
        result.setOverallRiskScore(5);
        result.setSummary(response);
        result.setRiskCategories(new ArrayList<>());
        result.setCriticalIssues(new ArrayList<>());
        result.setRecommendations(new ArrayList<>());
        return result;
    }

    private ComplianceValidationResult createFallbackComplianceValidation(String response, String jurisdiction) {
        ComplianceValidationResult result = new ComplianceValidationResult();
        result.setJurisdiction(jurisdiction);
        result.setOverallCompliant(false);
        result.setSummary(response);
        result.setPasses(new ArrayList<>());
        result.setFails(new ArrayList<>());
        result.setConcernAreas(new ArrayList<>());
        result.setRecommendations(new ArrayList<>());
        return result;
    }

    // Inner result classes

    public static class ContractAnalysisResult {
        private String jurisdiction;
        private String summary;
        private List<String> ambiguities;
        private List<ContractRisk> risks;
        private List<String> suggestions;
        private String overallRiskLevel;

        // Getters and Setters
        public String getJurisdiction() { return jurisdiction; }
        public void setJurisdiction(String jurisdiction) { this.jurisdiction = jurisdiction; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public List<String> getAmbiguities() { return ambiguities; }
        public void setAmbiguities(List<String> ambiguities) { this.ambiguities = ambiguities; }
        public List<ContractRisk> getRisks() { return risks; }
        public void setRisks(List<ContractRisk> risks) { this.risks = risks; }
        public List<String> getSuggestions() { return suggestions; }
        public void setSuggestions(List<String> suggestions) { this.suggestions = suggestions; }
        public String getOverallRiskLevel() { return overallRiskLevel; }
        public void setOverallRiskLevel(String overallRiskLevel) { this.overallRiskLevel = overallRiskLevel; }
    }

    public static class ContractRisk {
        private String description;
        private String severity;

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
    }

    public static class LegalResearchResult {
        private String query;
        private String jurisdiction;
        private String summary;
        private List<String> statutes;
        private List<LegalCase> cases;
        private List<String> principles;
        private List<String> recommendations;
        private List<String> sources;

        // Getters and Setters
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public String getJurisdiction() { return jurisdiction; }
        public void setJurisdiction(String jurisdiction) { this.jurisdiction = jurisdiction; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public List<String> getStatutes() { return statutes; }
        public void setStatutes(List<String> statutes) { this.statutes = statutes; }
        public List<LegalCase> getCases() { return cases; }
        public void setCases(List<LegalCase> cases) { this.cases = cases; }
        public List<String> getPrinciples() { return principles; }
        public void setPrinciples(List<String> principles) { this.principles = principles; }
        public List<String> getRecommendations() { return recommendations; }
        public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
        public List<String> getSources() { return sources; }
        public void setSources(List<String> sources) { this.sources = sources; }
    }

    public static class LegalCase {
        private String name;
        private String citation;
        private String relevance;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCitation() { return citation; }
        public void setCitation(String citation) { this.citation = citation; }
        public String getRelevance() { return relevance; }
        public void setRelevance(String relevance) { this.relevance = relevance; }
    }

    public static class RiskAssessmentResult {
        private int overallRiskScore;
        private List<RiskCategory> riskCategories;
        private List<String> criticalIssues;
        private List<String> recommendations;
        private String summary;

        // Getters and Setters
        public int getOverallRiskScore() { return overallRiskScore; }
        public void setOverallRiskScore(int overallRiskScore) { this.overallRiskScore = overallRiskScore; }
        public List<RiskCategory> getRiskCategories() { return riskCategories; }
        public void setRiskCategories(List<RiskCategory> riskCategories) { this.riskCategories = riskCategories; }
        public List<String> getCriticalIssues() { return criticalIssues; }
        public void setCriticalIssues(List<String> criticalIssues) { this.criticalIssues = criticalIssues; }
        public List<String> getRecommendations() { return recommendations; }
        public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
    }

    public static class RiskCategory {
        private String category;
        private int score;
        private String details;

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }
        public String getDetails() { return details; }
        public void setDetails(String details) { this.details = details; }
    }

    public static class ComplianceValidationResult {
        private String jurisdiction;
        private boolean overallCompliant;
        private List<CompliancePass> passes;
        private List<ComplianceFail> fails;
        private List<String> concernAreas;
        private List<String> recommendations;
        private String summary;

        // Getters and Setters
        public String getJurisdiction() { return jurisdiction; }
        public void setJurisdiction(String jurisdiction) { this.jurisdiction = jurisdiction; }
        public boolean isOverallCompliant() { return overallCompliant; }
        public void setOverallCompliant(boolean overallCompliant) { this.overallCompliant = overallCompliant; }
        public List<CompliancePass> getPasses() { return passes; }
        public void setPasses(List<CompliancePass> passes) { this.passes = passes; }
        public List<ComplianceFail> getFails() { return fails; }
        public void setFails(List<ComplianceFail> fails) { this.fails = fails; }
        public List<String> getConcernAreas() { return concernAreas; }
        public void setConcernAreas(List<String> concernAreas) { this.concernAreas = concernAreas; }
        public List<String> getRecommendations() { return recommendations; }
        public void setRecommendations(List<String> recommendations) { this.recommendations = recommendations; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
    }

    public static class CompliancePass {
        private String requirement;
        private String details;

        public String getRequirement() { return requirement; }
        public void setRequirement(String requirement) { this.requirement = requirement; }
        public String getDetails() { return details; }
        public void setDetails(String details) { this.details = details; }
    }

    public static class ComplianceFail {
        private String requirement;
        private String severity;
        private String details;
        private String recommendation;

        public String getRequirement() { return requirement; }
        public void setRequirement(String requirement) { this.requirement = requirement; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getDetails() { return details; }
        public void setDetails(String details) { this.details = details; }
        public String getRecommendation() { return recommendation; }
        public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    }
}

