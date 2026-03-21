# DESIGN.md  Claude Agent SDK Architecture for AI Legal Agent

> **Revision:** March 2026  
> **Status:** Proposed  
> **Prerequisite:** LangChain4J 0.25.0  1.12.2 upgrade

---

## 1. Executive Summary

Redesign the AI Legal Agent to follow **Claude Agent SDK** architectural patterns:

- **Agent Loop**  iterative prompt  evaluate  tool calls  execute  repeat
- **Subagents**  via `AgentDefinition(description, prompt, tools, model)` with flat hierarchy
- **Hooks**  typed lifecycle callbacks (`PreToolUse`, `PostToolUse`, `SubagentStart/Stop`, `Stop`)
- **MCP Servers**  all tools exposed via Model Context Protocol (`mcp__{server}__{tool}` naming)
- **Permission Chain**  Hooks  Deny  Mode  Allow  Callback
- **Sessions**  persistent conversation history with resume/fork

The Java Spring Boot backend becomes the **data persistence + compliance rules + document storage** layer, accessed by agents via MCP. The AI orchestration layer implements Claude Agent SDK patterns  either natively in Python/TypeScript (Option A) or as Java abstractions via LangChain4J 1.12.2 (Option B).

---

## 2. Architecture Decision: Hybrid vs. Java-Native

### Option A  Hybrid (Recommended for multi-client future)

```

     Claude Agent SDK (Python/TS)       
  Agent Loop, Subagents, Hooks,         
  Sessions, Permissions                 
               MCP (HTTP)              

     Spring Boot Backend (Java)         
  REST API, JPA, Security, Redis,       
  Document Storage, Compliance Rules    

```

- Agent SDK manages loop, subagents, hooks, sessions, permissions natively
- Spring Boot exposed as MCP server(s) over HTTP
- Full SDK compliance; usable from Claude Desktop, CI/CD, other AI clients

### Option B  Java-Native (Simpler, single-language)

- Implement Agent SDK patterns in Java using LangChain4J 1.12.2
- LangChain4J `AiServices` + `@Tool` annotations replicate agent loop
- Custom Java abstractions: `AgentDefinition`, `HookRegistry`, `SessionService`
- MCP Java SDK for compliance tools
- No Python dependency; requires implementing ~5 SDK primitives from scratch

**Recommendation:** Option B if team is Java-only. Option A if future Claude Desktop / multi-client integration is planned.

---

## 3. Current State vs. Target State

| Area | Current (0.25.0 / GPT-4o) | Target (1.12.2 / Claude) |
|---|---|---|
| LLM calls | Single monolithic call per method | Agent loop: iterative tool-use cycle |
| Output parsing | `extractJson()` + Jackson + 4 fallback methods | Tool use `strict:true`  schema-guaranteed |
| Architecture | Flat service layer | Orchestrator + 5 subagents |
| Compliance | Dead `performAiComplianceCheck()`; regex only | MCP server with autonomous tool loop |
| Research | LLM hallucinated citations | RAG + Claude Citations API |
| Quality gate | None | Evaluator subagent (opus model) |
| Audit | AOP interceptors | Hook-based lifecycle events |
| Permissions | Spring Security RBAC (endpoints only) | Tool-level permission chain |
| Sessions | None  stateless | Persistent with resume/fork |
| Streaming | `CompletableFuture.allOf().join()` blocks threads | SSE via `AnthropicStreamingChatModel` |
| Caching | 32-bit hashCode keys; bad results cached | SHA-256 keys; prompt caching (90% savings) |
| Cost control | None | `max_turns`, `max_budget_usd`, `effort` levels |

---

## 4. Architecture Diagram

```
                              
                                 Frontend (SPA /    
                                 Thymeleaf)         
                              
                                          SSE stream / REST
                              
                                DocumentController   
                                (202 Accepted +      
                                 session endpoints)  
                              
                                         
                              
                                LegalOrchestrator    
                                Agent (main query)   
                                tools: ["Agent"]     
                              
                                             
                       
                                                                
          
   contract-analyst     risk-scorer                   evaluator          
   (AgentDefinition)    (Agent           (AgentDefinition)  
   model: sonnet         Definition)   compliance-   model: opus        
   tools: flagRisk,     model:sonnet   checker       tools: validate    
    noteAmbiguity,      tools:         (Agent         Citation,         
    suggestEdit          scoreCateg,    Definition)   checkConsistency  
      addIssue      model:sonnet 
                            tools:                
                                           mcp__                
          compliance  
    legal-analysis-tools                   __*          Extended Thinking  
    MCP Server (in-process)                  (10K budget,      
    flagRisk, noteAmbiguity,                              HIGH-risk only)   
    suggestEdit, scoreCategory,         
    addCriticalIssue,                   compliance    
    validateCitation,                   MCP Server    
    checkConsistency                    (external)    
       check_juris.. 
                                          check_req..   
                  check_data..  
   legal-researcher                   
   (AgentDefinition)   
   model: sonnet               
   tools: vectorSearch,          legal-research-tools 
    citeCaseLaw,         MCP Server           
    citeStatute                  vectorSearch,        
            citeCaseLaw,         
                                   citeStatute          
                                 
                                            
                                 
                                  pgvector / PostgreSQL  
                                  (embeddings + data)    
                                 
```

---

## 5. Phase 0  Prerequisite: LangChain4J Upgrade

**Blocks all subsequent phases.**

| Dependency | Current | Target |
|---|---|---|
| `langchain4j` | 0.25.0 | 1.12.2 |
| `langchain4j-open-ai` | 0.25.0 | Remove or keep as fallback |
| `langchain4j-anthropic` | Not present | **Add** 1.12.2 |
| `langchain4j-mcp` | Not present | **Add** 1.12.2 |

### Steps

1. Update `agent/pom.xml` with new dependency versions
2. Switch `LegalAiService.init()` from `OpenAiChatModel`  `AnthropicChatModel`
3. Externalize model config from hardcoded values to `application.yml`:
   ```yaml
   ai:
     provider: anthropic
     api-key: ${ANTHROPIC_API_KEY}
     model: claude-sonnet-4-5
     temperature: 0.0
     timeout-seconds: 120
     max-tokens: 8192
   ```
4. Replace `openai.api-key` property references throughout

### Files Modified

- `agent/pom.xml`
- `agent/src/main/java/com/legalai/agent/service/LegalAiService.java`
- `agent/src/main/resources/application.yml`
- NEW: `agent/src/main/java/com/legalai/agent/config/AiModelConfig.java`

---

## 6. Phase 1  Agent Loop + Foundation Fixes

**Depends on Phase 0.**

Maps to: [Claude Agent SDK  How the Agent Loop Works](https://platform.claude.com/docs/en/agent-sdk/agent-loop)

The SDK's agent loop: prompt  evaluate  tool calls  execute  repeat until `stop_reason=end_turn` or limits hit. Currently `LegalAiService` makes a single LLM call per method with no iteration.

### Steps

1. **SHA-256 cache keys**  replace `#docText.hashCode()` in `@Cacheable` with SHA-256 digest
2. **Persist AI results as JSONB**  add columns to `Document` entity:
   - `analysisResult` (JSONB)
   - `riskAssessmentResult` (JSONB)
   - `complianceResult` (JSONB)
   - `analysisStatus` (enum: PENDING, RUNNING, COMPLETED, FAILED)
3. **Externalize prompts** to `resources/prompts/*.yaml` via `PromptTemplateService`
   - Equivalent to CLAUDE.md  persistent context loaded into every agent session
4. **Implement `AgentLoopService`**  core loop:
   ```
   while (turns < maxTurns && cost < maxBudget) {
       response = model.generate(messages, tools)
       if (response.hasToolCalls()) {
           results = executeTools(response.toolCalls())
           messages.add(response)
           messages.addAll(results)
           turns++
       } else {
           return response  // final answer, no more tool calls
       }
   }
   return ResultMessage(subtype=error_max_turns)
   ```
5. **Add `max_turns` and `max_budget_usd`**  configurable per analysis type
6. **Add `effort` levels**  matching Agent SDK's effort option:
   - `low`: quick compliance scan (~5 turns max)
   - `medium`: standard analysis (~15 turns max)
   - `high`: deep risk review (~30 turns max)
7. **Replace `extractJson()` + all four `createFallback*()`** methods with tool-use structured output

### Files Modified

- `agent/src/main/java/com/legalai/agent/service/LegalAiService.java`  refactor all 4 AI methods
- `agent/src/main/java/com/legalai/agent/entity/Document.java`  JSONB columns
- NEW: `agent/src/main/java/com/legalai/agent/service/AgentLoopService.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/PromptTemplateService.java`
- NEW: `agent/src/main/java/com/legalai/agent/config/AiModelConfig.java`
- NEW: `agent/src/main/resources/prompts/*.yaml`

---

## 7. Phase 2  Subagent Architecture

**Depends on Phase 1.**

Maps to: [Claude Agent SDK  Subagents](https://platform.claude.com/docs/en/agent-sdk/subagents)

### SDK Constraints (these override the original design)

| Constraint | Impact |
|---|---|
| **Subagents cannot nest**  flat, one level deep | Evaluator cannot be a "loop within a loop"; it's a peer subagent |
| **Isolated context**  parent conversation NOT shared | Each subagent starts fresh; only the Agent tool's prompt string passes info |
| **Only final message returns** to parent | Intermediate tool calls stay inside subagent; parent context grows by ~1 summary |
| **Claude auto-delegates** via `description` field | No explicit routing code needed; write clear descriptions |
| **Parallel execution** for independent tasks | contract-analyst + compliance-checker + legal-researcher run simultaneously |

### Subagent Definitions

```java
// Java equivalent of AgentDefinition
public record AgentDefinition(
    String name,
    String description,    // triggers auto-delegation
    String promptTemplate, // path to resources/prompts/{name}.yaml
    List<String> tools,    // scoped tool list
    String model           // "sonnet", "opus", "haiku"
) {}
```

| Name | Description (for auto-delegation) | Tools (scoped) | Model |
|---|---|---|---|
| `contract-analyst` | "Contract analysis specialist. Use for identifying risks, ambiguities, and suggesting edits in legal documents." | `mcp__legal-analysis__flagRisk`, `mcp__legal-analysis__noteAmbiguity`, `mcp__legal-analysis__suggestEdit` | sonnet |
| `risk-scorer` | "Risk scoring specialist. Use for quantitative risk assessment and severity categorization." | `mcp__legal-analysis__scoreCategory`, `mcp__legal-analysis__addCriticalIssue` | sonnet |
| `compliance-checker` | "Compliance verification specialist. Use for checking documents against jurisdiction-specific regulations." | `mcp__compliance__*` (all compliance MCP tools) | sonnet |
| `legal-researcher` | "Legal research specialist. Use for finding relevant case law, statutes, and precedents." | `mcp__research__vectorSearch`, `mcp__research__citeCaseLaw`, `mcp__research__citeStatute` | sonnet |
| `evaluator` | "Quality assurance evaluator. Use after analysis to verify accuracy, check for hallucinated citations, and validate jurisdiction correctness." | `mcp__legal-analysis__validateCitation`, `mcp__legal-analysis__checkConsistency` (read-only) | opus |

### Evaluator Design (Changed from Original)

The original design proposed an Evaluator-Optimizer feedback loop with max 2 revision passes. Under the Claude Agent SDK's flat-hierarchy constraint, this becomes:

1. Orchestrator invokes `evaluator` subagent with analysis results in the prompt
2. Evaluator returns confidence score + list of issues
3. If `confidence < 7`, orchestrator re-invokes the relevant worker subagent with evaluator feedback in the prompt string
4. This stays flat  no nesting, no inner loop

Extended Thinking is configured on the evaluator's model:

```java
AnthropicChatModel evaluatorModel = AnthropicChatModel.builder()
    .apiKey(apiKey)
    .modelName("claude-opus-4")
    .thinkingType("enabled")       // enable extended thinking
    .thinkingBudgetTokens(10000)   // 10K token budget
    .build();
```

**Extended Thinking gating:** Only activated for documents flagged HIGH risk by the initial risk-scorer. For other documents, use `thinkingType("adaptive")` to let Claude decide.

### Execution Flow

```
Document uploaded
        
Orchestrator receives document text + jurisdiction
        
Auto-delegates (parallel where possible):
   contract-analyst   final summary (risks, ambiguities, edits)
   compliance-checker  final summary (violations, missing clauses)
   legal-researcher    final summary (citations, precedents)
            (waits for contract-analyst)
   risk-scorer         final summary (scores, critical issues)
        
Orchestrator checks risk level from risk-scorer
        
If HIGH risk: invoke evaluator subagent
        
Evaluator returns confidence + issues
        
If confidence < 7: re-invoke relevant worker with feedback
        
Orchestrator synthesizes final report
```

### Files

- NEW: `agent/src/main/java/com/legalai/agent/service/agent/AgentDefinition.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/agent/SubagentExecutor.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/agent/LegalOrchestratorAgent.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/agent/ContractAnalysisAgent.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/agent/RiskScoringAgent.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/agent/ComplianceCheckAgent.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/agent/LegalResearchAgent.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/agent/EvaluatorAgent.java`
- NEW: `agent/src/main/resources/prompts/contract-analyst.yaml`
- NEW: `agent/src/main/resources/prompts/risk-scorer.yaml`
- NEW: `agent/src/main/resources/prompts/compliance-checker.yaml`
- NEW: `agent/src/main/resources/prompts/legal-researcher.yaml`
- NEW: `agent/src/main/resources/prompts/evaluator.yaml`

---

## 8. Phase 3  Hooks System

**Parallel with Phase 2.**

Maps to: [Claude Agent SDK  Control Execution with Hooks](https://platform.claude.com/docs/en/agent-sdk/hooks)

Replace AOP-based `ActivityMonitorService` with SDK-style hooks. Hooks are callbacks at lifecycle points with typed inputs/outputs.

### Hook Events

| Event | SDK Equivalent | Fires When | Can Block? |
|---|---|---|---|
| `PRE_TOOL_USE` | `PreToolUse` | Before tool executes | Yes  `deny` / `allow` / `ask` |
| `POST_TOOL_USE` | `PostToolUse` | After tool returns | No  audit/logging only |
| `POST_TOOL_USE_FAILURE` | `PostToolUseFailure` | After tool fails | No  error tracking |
| `SUBAGENT_START` | `SubagentStart` | Subagent spawned | No  tracking |
| `SUBAGENT_STOP` | `SubagentStop` | Subagent completed | No  result aggregation |
| `STOP` | `Stop` | Agent loop ends | No  cleanup/save |

### Hook Implementations

| Hook Class | Event | Matcher | Purpose | Replaces |
|---|---|---|---|---|
| `AuditHook` | `POST_TOOL_USE` | `.*` (all) | Log tool name, args, result, user to `AuditLog` | `ActivityMonitorService` AOP `@AfterReturning` |
| `ErrorAuditHook` | `POST_TOOL_USE_FAILURE` | `.*` | Log failures with stack traces | `ActivityMonitorService` AOP `@AfterThrowing` |
| `PermissionHook` | `PRE_TOOL_USE` | `.*` | Enforce RBAC per tool via `PermissionEvaluator` | New capability |
| `PiiScanHook` | `PRE_TOOL_USE` | `^mcp__legal-analysis__` | Scan tool inputs for PII before LLM processing | Moves PII scan earlier |
| `SubagentTrackerHook` | `SUBAGENT_START/STOP` | N/A | Log start/stop, measure duration | New capability |
| `SessionSaveHook` | `STOP` | N/A | Persist final results to Document JSONB | New capability |

### Hook Registration (mirrors SDK's `ClaudeAgentOptions.hooks`)

```java
HookRegistry registry = HookRegistry.builder()
    .on(PRE_TOOL_USE, HookMatcher.of(".*", permissionHook))        // check perms
    .on(PRE_TOOL_USE, HookMatcher.of("^mcp__legal-analysis__", piiScanHook))
    .on(POST_TOOL_USE, HookMatcher.of(".*", auditHook))            // audit all
    .on(POST_TOOL_USE_FAILURE, HookMatcher.of(".*", errorAuditHook))
    .on(SUBAGENT_START, HookMatcher.all(subagentTrackerHook))
    .on(SUBAGENT_STOP, HookMatcher.all(subagentTrackerHook))
    .on(STOP, HookMatcher.all(sessionSaveHook))
    .build();
```

### Hook Callback Interface

```java
@FunctionalInterface
public interface HookCallback {
    HookOutput execute(HookInput input, String toolUseId);
}

public record HookOutput(
    String systemMessage,           // injected into conversation
    boolean continue_,              // should agent keep running?
    HookSpecificOutput specific     // event-specific: permissionDecision, updatedInput, etc.
) {}
```

### Permission Evaluation Chain (mirrors SDK exactly)

```
Tool call requested by agent
    
    
 1. PreToolUse hooks 
  PermissionHook checks role + tool     
   returns allow / deny / continue     

                      if not resolved
                     
 2. Deny rules (disallowed_tools) 
  Explicit blocklist per role           

                      if not denied
                     
 3. Permission mode 
  ADMIN  bypassPermissions            
  LAWYER  acceptEdits                 
  CLERK  plan (read-only)             

                      if not resolved
                     
 4. Allow rules (allowed_tools) 
  Pre-approved tools per role           

                      if not resolved
                     
 5. canUseTool callback 
  Runtime approval (or deny)            

```

### Files

- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/HookEvent.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/HookCallback.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/HookMatcher.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/HookInput.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/HookOutput.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/HookRegistry.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/AuditHook.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/PermissionHook.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/PiiScanHook.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/SubagentTrackerHook.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/hooks/SessionSaveHook.java`
- DEPRECATE: ActivityMonitorService.java (AOP  hooks)

---

## 9. Phase 4  Custom Tools as MCP Servers

**Depends on Phase 1. Parallel with Phase 2.**

Maps to: [Claude Agent SDK  Custom Tools](https://platform.claude.com/docs/en/agent-sdk/custom-tools) + [MCP](https://platform.claude.com/docs/en/agent-sdk/mcp)

All tools follow `mcp__{server-name}__{tool-name}` naming convention.

### 4a. `legal-analysis-tools` MCP Server (in-process)

Replaces `extractJson()` + all fallback methods. Each output type is a typed tool.

| Tool | Parameters | Returns | Used By |
|---|---|---|---|
| `flagRisk` | `clause: string`, `severity: HIGH\|MEDIUM\|LOW`, `description: string` | void (collected by agent) | contract-analyst |
| `noteAmbiguity` | `clause: string`, `explanation: string` | void | contract-analyst |
| `suggestEdit` | `clause: string`, `suggestion: string` | void | contract-analyst |
| `scoreCategory` | `category: string`, `score: 0-10`, `rationale: string` | void | risk-scorer |
| `addCriticalIssue` | `issue: string`, `impact: string` | void | risk-scorer |
| `validateCitation` | `citation: string`, `jurisdiction: string` | `{valid: bool, reason: string}` | evaluator |
| `checkConsistency` | `findings: string` | `{consistent: bool, issues: string[]}` | evaluator |

With `strict: true`, Claude's tool inputs are **schema-guaranteed**  no malformed JSON, no silent degradation.

### 4b. `compliance` MCP Server (external process)

Uses existing `ComplianceEngineService` logic, exposed via MCP protocol.

| Tool | Parameters | Returns |
|---|---|---|
| `check_jurisdiction_rules` | `text: string`, `jurisdiction: string` | `violations[]` |
| `check_required_clauses` | `text: string`, `jurisdiction: string` | `{present[], missing[]}` |
| `check_data_protection` | `text: string` | `{piiFound[], recommendations[]}` |
| `get_compliance_rules` | `jurisdiction: string` | `rules[]` |

**Resources** (MCP read-only data):
- `compliance-rules://US-CA`  latest CCPA rules
- `compliance-rules://EU`  latest GDPR rules
- `compliance-rules://US-NY`  latest NY-specific rules

**Transport:** stdio initially (same host), HTTP when independently deployed.

### 4c. `legal-research` MCP Server (in-process)

Integrates pgvector + Claude Citations API.

| Tool | Parameters | Returns |
|---|---|---|
| `vectorSearch` | `query: string`, `jurisdiction: string`, `topK: int` | `chunks[]` |
| `citeCaseLaw` | `query: string`, `jurisdiction: string` | Citations-enabled response |
| `citeStatute` | `query: string`, `jurisdiction: string` | Citations-enabled response |

### Citations vs. Tool Use Routing (Hard Constraint)

Claude returns HTTP 400 if both Citations and Structured Outputs (`strict: true`) are enabled simultaneously.

**Design solution:**
- **Tool use mode**  contract-analyst, risk-scorer, compliance-checker, evaluator
- **Citations mode**  legal-researcher

The orchestrator routes to the appropriate mode based on which subagent is active.

### Autonomous Tool Loop (compliance-checker example)

```
compliance-checker receives document + jurisdiction "US-CA"
    
Think: "This is a California contract. Check CCPA rules."
    
Call: mcp__compliance__check_jurisdiction_rules(text, "US-CA")
     3 violations found
    
Think: "Found 3 violations. Now check required clauses."
    
Call: mcp__compliance__check_required_clauses(text, "US-CA")
     missing: privacy notice, opt-out mechanism
    
Think: "Let me also scan for data protection issues."
    
Call: mcp__compliance__check_data_protection(text)
     2 emails, 1 SSN found
    
Think: "I have all findings. Compile the report."
    
Return: structured ComplianceResult (final message to orchestrator)
```

### Files

- NEW: `agent/src/main/java/com/legalai/agent/mcp/LegalAnalysisMcpServer.java`
- NEW: `agent/src/main/java/com/legalai/agent/mcp/ComplianceMcpServer.java`
- NEW: `agent/src/main/java/com/legalai/agent/mcp/LegalResearchMcpServer.java`
- NEW: `agent/src/main/java/com/legalai/agent/config/McpConfig.java`
- MODIFY: ComplianceEngineService.java  extract tool logic

---

## 10. Phase 5  Permission Model

**Depends on Phase 3 (Hooks).**

Maps to: [Claude Agent SDK  Configure Permissions](https://platform.claude.com/docs/en/agent-sdk/permissions)

### Role-to-Permission-Mode Mapping

| Spring Security Role | SDK Permission Mode | `allowed_tools` | `disallowed_tools` |
|---|---|---|---|
| `ADMIN` | `bypassPermissions` | all tools | none |
| `LAWYER` | `acceptEdits` | all analysis + research MCP tools | admin tools, delete operations |
| `CLERK` | `plan` (read-only) | `mcp__research__*`, read-only tools | all write/modify/execute tools |

**Caution (from SDK docs):** `bypassPermissions` propagates to all subagents and cannot be overridden. ADMIN role therefore grants full autonomous access.

### Files

- NEW: `agent/src/main/java/com/legalai/agent/security/PermissionMode.java`
- NEW: `agent/src/main/java/com/legalai/agent/security/PermissionEvaluator.java`
- NEW: `agent/src/main/java/com/legalai/agent/security/AllowedToolsResolver.java`
- MODIFY: RoleBasedAccessService.java

---

## 11. Phase 6  Session Management

**Parallel with Phase 4.**

Maps to: [Claude Agent SDK  Work with Sessions](https://platform.claude.com/docs/en/agent-sdk/sessions)

### Session Operations

| Operation | SDK Equivalent | Description |
|---|---|---|
| `createSession` | New `query()` | Start fresh analysis session for a document |
| `resumeSession` | `resume=session_id` | Continue with follow-up prompt (full prior context) |
| `forkSession` | `fork_session=True` | Branch into alternative analysis (original unchanged) |
| `listSessions` | `list_sessions()` | All sessions for a user |

### Session Entity

```java
@Entity
public class AgentSession {
    @Id @GeneratedValue
    private Long id;

    @Column(unique = true)
    private String sessionId;          // UUID

    private String userId;
    private Long documentId;

    @Enumerated(EnumType.STRING)
    private SessionStatus status;      // ACTIVE, COMPLETED, ERROR, FORKED

    @Column(columnDefinition = "jsonb")
    private String conversationHistory; // full message transcript

    private Integer numTurns;
    private BigDecimal totalCostUsd;

    @Column(columnDefinition = "jsonb")
    private String finalResult;        // final analysis output

    private String forkedFromSessionId; // null unless forked

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### Automatic Compaction

When session conversation exceeds context threshold (~150K tokens):
1. Summarize older turns, keep recent exchanges + key decisions
2. Inject summary as first message
3. Mirrors Agent SDK's `compact_boundary` system message

### New API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/docs/{id}/analyze` | Start new analysis session  returns `{sessionId}` |
| `POST` | `/sessions/{id}/continue` | Continue with follow-up prompt |
| `POST` | `/sessions/{id}/fork` | Fork for alternative exploration |
| `GET` | `/sessions/{id}` | Get session state + messages |
| `GET` | `/sessions` | List user's sessions |
| `GET` | `/sessions/{id}/cost` | Get cost breakdown for session |

### Files

- NEW: `agent/src/main/java/com/legalai/agent/entity/AgentSession.java`
- NEW: `agent/src/main/java/com/legalai/agent/repository/AgentSessionRepository.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/AgentSessionService.java`
- MODIFY: DocumentController.java

---

## 12. Phase 7  RAG + Embeddings + Citations

**Depends on Phase 4c.**

### Steps

1. Enable **pgvector** extension on existing PostgreSQL
2. Create `DocumentChunkerService`  semantic chunks ~2K tokens, 200-token overlap
3. Create `EmbeddingService`  LangChain4J `EmbeddingModel` with Anthropic or OpenAI embeddings
4. Create `DocumentChunk` entity + `DocumentChunkRepository`
5. Legal-researcher subagent flow:
   ```
   query  EmbeddingService.embed(query)  pgvector ANN search  top-10 chunks
        inject as Claude "document" content blocks with citations: enabled
        Claude responds with structured citation objects (char indices, cited_text)
   ```
6. **Cited text doesn't count as output tokens**  cost saving

### Citation Response Structure

```json
{
  "type": "text",
  "text": "Under CCPA, consumers have the right to know...",
  "citations": [{
    "type": "document",
    "document_index": 0,
    "start_char_index": 142,
    "end_char_index": 287,
    "cited_text": "A consumer shall have the right to request..."
  }]
}
```

### Files

- NEW: `agent/src/main/java/com/legalai/agent/entity/DocumentChunk.java`
- NEW: `agent/src/main/java/com/legalai/agent/repository/DocumentChunkRepository.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/DocumentChunkerService.java`
- NEW: `agent/src/main/java/com/legalai/agent/service/EmbeddingService.java`
- MODIFY: `agent/src/main/java/com/legalai/agent/mcp/LegalResearchMcpServer.java`

---

## 13. Phase 8  Streaming + Cost Controls

**Depends on Phase 1.**

### 8a. SSE Streaming

Replace `CompletableFuture.allOf().join()` (blocks Tomcat threads) with SSE:

```java
@GetMapping(value = "/{id}/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter analyzeStreaming(@PathVariable Long id) {
    SseEmitter emitter = new SseEmitter(180_000L);
    // agent loop streams tokens via emitter.send()
    return emitter; // returns immediately
}
```

Upload returns `202 Accepted` + status URL; AI runs asynchronously.

### 8b. Prompt Caching (90% savings on cached tokens)

```java
AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(apiKey)
    .modelName("claude-sonnet-4-5")
    .cacheSystemMessages(true)   // system prompt cached after first call
    .cacheTools(true)            // tool definitions cached
    .build();
```

For compliance checks where system prompt + rules = ~2000 tokens:
- First call: 2000  1.25 = 2500 token-equivalents (cache write)
- Subsequent: 2000  0.10 = **200 token-equivalents** (90% savings)

### 8c. Batch API (50% cost for bulk processing)

For analyzing entire deal rooms overnight:

```
POST /v1/messages/batches
{
  "requests": [
    {"custom_id": "doc-1", "params": { ... }},
    {"custom_id": "doc-2", "params": { ... }}
    // up to 100,000 requests per batch
  ]
}
```

Batch (50%) + prompt caching (90% on cached tokens) stack for massive bulk savings.

### 8d. Cost Tracking

Track per session (mirrors Agent SDK's `ResultMessage.total_cost_usd`):
- Input tokens, output tokens, cache read tokens, cache write tokens
- Thinking tokens (Extended Thinking  billed at full input rate)
- Stored on `AgentSession.totalCostUsd`

### Files

- MODIFY: DocumentController.java
- NEW: `agent/src/main/java/com/legalai/agent/event/DocumentUploadedEvent.java`
- NEW: `agent/src/main/java/com/legalai/agent/event/DocumentEventListeners.java`
- MODIFY: `agent/src/main/java/com/legalai/agent/config/AiModelConfig.java`

---

## 14. Verification Plan

### Unit Tests

| Test | Validates |
|---|---|
| `AgentLoopServiceTest` | Loop terminates on end_turn; respects max_turns; respects max_budget_usd |
| `SubagentExecutorTest` | Isolated context; only final message returns; parallel execution |
| `HookRegistryTest` | Matcher regex filtering; execution order; deny overrides allow |
| `PermissionEvaluatorTest` | Full chain: hooks  deny  mode  allow  callback |
| `AgentSessionServiceTest` | Create  resume  fork  verify independence |
| Each subagent test | Correct tool calls with mocked MCP; no JSON parsing |

### Integration Tests

| Test | Validates |
|---|---|
| Full NDA analysis | Orchestrator auto-delegates to correct subagents; result persisted as JSONB |
| GDPR DPA analysis | Compliance-checker subagent invoked; legal-researcher invoked |
| Simple NDA | Only contract-analyst + risk-scorer invoked (subagent economy) |
| MCP compliance server | Client  `check_jurisdiction_rules("US-CA")`  structured violations |

### Permission Tests

| Test | Validates |
|---|---|
| CLERK invokes write tool | Denied by permission chain |
| LAWYER analyzes document | Allowed; all analysis MCP tools accessible |
| ADMIN bypasses all | `bypassPermissions` mode; all tools approved |

### Session Tests

| Test | Validates |
|---|---|
| Create + continue | Follow-up prompt sees prior analysis context |
| Fork | Original session unchanged; fork has independent history |
| Cost tracking | `totalCostUsd` populated on session completion |

### Regression

- Existing `DocumentServiceTest` (~10 tests) still passes after Phase 0 upgrade

---

## 15. Anthropic Capabilities  Project Gaps Summary

| Project Gap | Anthropic Capability | Phase | SDK Primitive |
|---|---|---|---|
| Fragile JSON parsing | Tool Use (`strict: true`) | 1 | Agent Loop  tool execution |
| Single monolithic LLM call | Orchestrator-Workers | 2 | Subagents (`AgentDefinition`) |
| No quality gate | Evaluator subagent + Extended Thinking | 2 | Subagents (opus model) |
| Hallucinated citations | Citations API + RAG | 7 | MCP tools + Citations |
| Dead AI compliance | MCP Server + Autonomous Tool Loop | 4 | MCP + Agent Loop |
| Regex can't detect missing clauses | `check_required_clauses` MCP tool | 4 | Custom MCP tool |
| AOP-based audit (fragile) | Hook-based lifecycle events | 3 | Hooks (`PostToolUse`) |
| No tool-level permissions | Permission evaluation chain | 5 | Permissions |
| No conversation persistence | Session management | 6 | Sessions (resume/fork) |
| Blocking HTTP threads | SSE Streaming | 8 | `AnthropicStreamingChatModel` |
| Re-running LLM on every request | Prompt Caching (90% savings) | 8 | `cacheSystemMessages` |
| No bulk processing | Batch API (50% cost) | 8 | Messages Batch API |
| No cost controls | `max_turns`, `max_budget_usd`, `effort` | 1 | Agent Loop options |
| 128K token limit (GPT-4o) | Claude 200K context window | 0 | Model switch |
| LangChain4J 0.25.0 | Upgrade to 1.12.2 | 0 | Prerequisite |

---

## 16. Key Decisions

1. **Subagents are flat**  evaluator is a peer, not a nested loop. Re-invocation passes feedback in the prompt string.
2. **Citations and Tool Use are incompatible**  hard routing: tool use for analysis, citations for research. This is a Claude API constraint (HTTP 400).
3. **Prompts in `resources/prompts/*.yaml`**  equivalent to CLAUDE.md. Persistent context loaded into every agent session.
4. **Extended Thinking**  evaluator subagent only, opus model, HIGH-risk documents only. Use `adaptive` type for standard documents.
5. **Extended Thinking cost**  thinking tokens billed at full input rate. ~$0.03/evaluation at 10K tokens. Restrict to HIGH-risk.
6. **MCP compliance server**  start with stdio transport (same-process). Migrate to HTTP when compliance team wants independent deployment.
7. **`bypassPermissions` propagates to all subagents** (SDK constraint). ADMIN role = full autonomous access.
8. **Context window budget**  5 subagents  ~10K summary each = 50K tokens in orchestrator. Leaves 150K for document + system prompt. Sufficient for most legal documents without chunking.

---

## 17. Phase Dependencies

```
Phase 0 (Prerequisite)
    
     Phase 1 (Agent Loop)
           
            Phase 2 (Subagents)
                  
                   Phase 5 (Permissions)  Phase 3 (Hooks)
           
            Phase 4 (MCP Tools)  Phase 7 (RAG + Citations)
           
            Phase 8 (Streaming + Cost)
    
     Phase 3 (Hooks)  parallel with Phase 2
    
     Phase 6 (Sessions)  parallel with Phase 4
```

Phases 2, 3, 4, 6, and 8 can be developed in parallel after Phase 1 is complete.
Phase 5 requires both Phase 2 and Phase 3.
Phase 7 requires Phase 4c.

---

## 18. File Inventory

### New Files (34 files)

| Path | Phase |
|---|---|
| `config/AiModelConfig.java` | 0 |
| `config/McpConfig.java` | 4 |
| `service/AgentLoopService.java` | 1 |
| `service/PromptTemplateService.java` | 1 |
| `service/agent/AgentDefinition.java` | 2 |
| `service/agent/SubagentExecutor.java` | 2 |
| `service/agent/LegalOrchestratorAgent.java` | 2 |
| `service/agent/ContractAnalysisAgent.java` | 2 |
| `service/agent/RiskScoringAgent.java` | 2 |
| `service/agent/ComplianceCheckAgent.java` | 2 |
| `service/agent/LegalResearchAgent.java` | 2 |
| `service/agent/EvaluatorAgent.java` | 2 |
| `service/hooks/HookEvent.java` | 3 |
| `service/hooks/HookCallback.java` | 3 |
| `service/hooks/HookMatcher.java` | 3 |
| `service/hooks/HookInput.java` | 3 |
| `service/hooks/HookOutput.java` | 3 |
| `service/hooks/HookRegistry.java` | 3 |
| `service/hooks/AuditHook.java` | 3 |
| `service/hooks/PermissionHook.java` | 3 |
| `service/hooks/PiiScanHook.java` | 3 |
| `service/hooks/SubagentTrackerHook.java` | 3 |
| `service/hooks/SessionSaveHook.java` | 3 |
| `mcp/LegalAnalysisMcpServer.java` | 4 |
| `mcp/ComplianceMcpServer.java` | 4 |
| `mcp/LegalResearchMcpServer.java` | 4 |
| `security/PermissionMode.java` | 5 |
| `security/PermissionEvaluator.java` | 5 |
| `security/AllowedToolsResolver.java` | 5 |
| `entity/AgentSession.java` | 6 |
| `repository/AgentSessionRepository.java` | 6 |
| `service/AgentSessionService.java` | 6 |
| `entity/DocumentChunk.java` | 7 |
| `repository/DocumentChunkRepository.java` | 7 |
| `service/DocumentChunkerService.java` | 7 |
| `service/EmbeddingService.java` | 7 |
| `event/DocumentUploadedEvent.java` | 8 |
| `event/DocumentEventListeners.java` | 8 |
| `resources/prompts/*.yaml` (5 files) | 2 |

All paths relative to agent.

### Modified Files (8 files)

| Path | Phase | Changes |
|---|---|---|
| pom.xml | 0 | Dependency upgrades |
| `service/LegalAiService.java` | 1 | Refactor  delegate to AgentLoopService |
| `entity/Document.java` | 1 | Add JSONB columns |
| `resources/application.yml` | 0 | Externalize AI config |
| `service/ComplianceEngineService.java` | 4 | Extract tool logic for MCP |
| `service/RoleBasedAccessService.java` | 5 | Integrate permission modes |
| `controller/DocumentController.java` | 6, 8 | Session endpoints + SSE |
| `service/ActivityMonitorService.java` | 3 | Deprecate AOP, delegate to hooks |