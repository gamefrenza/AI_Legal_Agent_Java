# Building Autonomous AI Agents with the Claude Agent SDK

## A Comprehensive Developer's Guide

---

## Introduction

Anthropic's **Claude Agent SDK** (formerly the Claude Code SDK) is a Python and TypeScript library that gives you programmatic access to the same autonomous agent loop that powers Claude Code. Rather than sending a prompt and parsing a single response, the Agent SDK lets Claude autonomously call tools, observe results, adjust its approach, and iterate  completing multi-step tasks without manual orchestration.

The fundamental difference from the standard Anthropic Client SDK:

```python
# Client SDK: YOU implement the tool loop
response = client.messages.create(...)
while response.stop_reason == "tool_use":
    result = your_tool_executor(response.tool_use)
    response = client.messages.create(tool_result=result, **params)

# Agent SDK: Claude handles tools autonomously
async for message in query(prompt="Fix the bug in auth.py"):
    print(message)  # Claude reads, reasons, edits  you just watch
```

This guide covers the full SDK surface: the agent loop, subagents, hooks, MCP servers, permissions, sessions, context management, and Java integration via LangChain4J.

**Package references:**
- TypeScript: `npm install @anthropic-ai/claude-agent-sdk`
- Python: `pip install claude-agent-sdk`
- Docs: https://platform.claude.com/docs/en/api/agent-sdk/overview

---

## Chapter 1: The Agent Loop

Every Agent SDK session follows the same execution cycle. Understanding this cycle is prerequisite to everything else.

### The Cycle

```
1. Receive prompt
    SDK yields SystemMessage(subtype="init") with session metadata

2. Claude evaluates and responds
    SDK yields AssistantMessage with text + any tool call requests

3. SDK executes tools
    Hooks run first (can block/modify)
    SDK yields UserMessage with tool results fed back to Claude

4. Repeat steps 2-3
    Each round trip = one "turn"
    Continues until Claude produces output with no tool calls

5. Return result
    SDK yields final AssistantMessage (text only)
    SDK yields ResultMessage (always last) with final text, cost, usage, session_id
```

### Message Types

| Type | When | Key Fields |
|---|---|---|
| `SystemMessage` | Session start; after compaction | `subtype: "init"` or `"compact_boundary"` |
| `AssistantMessage` | After each Claude response | `content[]`  text blocks + tool use blocks |
| `UserMessage` | After each tool execution | Tool result content |
| `StreamEvent` | During streaming (opt-in) | Raw deltas, tool input chunks |
| `ResultMessage` | Always last | `result`, `subtype`, `total_cost_usd`, `session_id`, `num_turns` |

### ResultMessage Subtypes

```python
if isinstance(message, ResultMessage):
    match message.subtype:
        case "success":
            print(f"Done: {message.result}")
        case "error_max_turns":
            print(f"Hit turn limit. Resume: {message.session_id}")
        case "error_max_budget_usd":
            print("Hit cost cap.")
        case "error_during_execution":
            print("API failure or cancellation.")
        case "error_max_structured_output_retries":
            print("Structured output validation failed.")
```

`result` is only present on `success`. Always check `subtype` first. All subtypes carry `total_cost_usd`, `session_id`, and `num_turns`  track cost and resume even after errors.

### Loop Controls

All set via `ClaudeAgentOptions` (Python) or the `options` object (TypeScript):

```python
options = ClaudeAgentOptions(
    max_turns=30,               # cap tool-use round trips; default = no limit
    max_budget_usd=0.50,        # cost cap; default = no limit
    effort="high",              # "low" | "medium" | "high" | "max"  reasoning depth
    model="claude-sonnet-4-5",  # pin specific model
)
```

### Effort Levels

| Level | Reasoning | Tokens | Use When |
|---|---|---|---|
| `"low"` | Minimal | Fewest | File lookups, listing directories |
| `"medium"` | Balanced | Moderate | Routine edits, standard tasks |
| `"high"` | Thorough | More | Refactors, debugging |
| `"max"` | Maximum depth | Most | Multi-step problems requiring deep analysis |

> **Note:** `effort` is independent of Extended Thinking. `effort` controls reasoning depth within a single response; Extended Thinking generates visible chain-of-thought blocks. You can combine them freely.

### A Complete Agent

```python
import asyncio
from claude_agent_sdk import query, ClaudeAgentOptions, ResultMessage

async def run_agent():
    session_id = None

    async for message in query(
        prompt="Find and fix the failing test in auth_test.py",
        options=ClaudeAgentOptions(
            allowed_tools=["Read", "Edit", "Bash", "Glob", "Grep"],
            setting_sources=["project"],  # load CLAUDE.md and .claude/ config
            max_turns=30,
            effort="high",
        ),
    ):
        if isinstance(message, ResultMessage):
            session_id = message.session_id

            if message.subtype == "success":
                print(f"Done: {message.result}")
                if message.total_cost_usd is not None:
                    print(f"Cost: ${message.total_cost_usd:.4f}")
            elif message.subtype == "error_max_turns":
                print(f"Hit turn limit. Resume with session: {session_id}")
            else:
                print(f"Stopped: {message.subtype}")

asyncio.run(run_agent())
```

---

## Chapter 2: Built-in Tools

The Agent SDK ships with Claude Code's full tool set. No implementation required  the SDK handles execution.

### Tool Reference

| Tool | Category | Purpose |
|---|---|---|
| `Read` | File | Read any file in working directory |
| `Edit` | File | Make precise edits to existing files |
| `Write` | File | Create new files |
| `Glob` | Search | Find files by pattern (`**/*.ts`, `src/**/*.py`) |
| `Grep` | Search | Search file contents with regex |
| `Bash` | Execute | Run shell commands, scripts, git operations |
| `WebSearch` | Web | Search the web for current information |
| `WebFetch` | Web | Fetch and parse web page content |
| `Agent` | Orchestration | Spawn subagents (required to use subagents) |
| `Skill` | Orchestration | Invoke filesystem-defined skills |
| `AskUserQuestion` | Interaction | Ask clarifying questions with multiple-choice options |
| `TodoWrite` | Tracking | Maintain a task list within the session |
| `ToolSearch` | Discovery | Dynamically load MCP tools on-demand (large tool sets) |

### Parallel vs. Sequential Execution

When Claude requests multiple tool calls in one turn:
- **Read-only tools** (`Read`, `Glob`, `Grep`, read-only MCP tools)  run **concurrently**
- **State-modifying tools** (`Edit`, `Write`, `Bash`)  run **sequentially** to avoid conflicts
- **Custom tools**  sequential by default; set `readOnlyHint=True` (Python) to enable parallel

### Controlling Tool Access

```python
options = ClaudeAgentOptions(
    # Pre-approve these tools (no permission prompt required)
    allowed_tools=["Read", "Glob", "Grep", "Edit"],

    # Block these tools entirely  enforced even in bypassPermissions mode
    disallowed_tools=["Bash"],
)
```

Scoped rules: `"Bash(npm:*)"` allows only npm commands via Bash.

---

## Chapter 3: Subagents

Subagents are the SDK's mechanism for context isolation and parallelization. The main agent spawns subagents to handle focused subtasks; each runs in its own fresh conversation.

### Why Use Subagents

**Context isolation:** A `research-assistant` subagent can explore dozens of files without any of that content accumulating in the main agent's context window. The parent receives a concise summary  not every file the subagent read.

**Parallelization:** Multiple subagents run concurrently. A code review that takes 3 minutes sequentially (style + security + test coverage) takes 1 minute with parallel subagents.

**Specialization:** Each subagent gets tailored system prompts with domain-specific expertise and minimal tool access, reducing the risk of unintended actions.

### Hard Constraints

These constraints are architectural  they cannot be worked around:

1. **Subagents cannot nest.** The hierarchy is flat: orchestrator  subagents. A subagent cannot spawn its own subagents. Never include `Agent` in a subagent's `tools` list.
2. **Isolated context.** The subagent does NOT see the parent's conversation history or tool results. The only channel from parent to subagent is the Agent tool's prompt string.
3. **Only the final message returns.** All intermediate tool calls stay inside the subagent. The parent's context grows by one summary, not the subagent's full transcript.
4. **Claude auto-delegates based on `description`.** Write clear, specific descriptions  that's what Claude matches to decide which subagent to invoke.

### Defining Subagents

```python
from claude_agent_sdk import query, ClaudeAgentOptions, AgentDefinition

async for message in query(
    prompt="Review the authentication module for security issues",
    options=ClaudeAgentOptions(
        # "Agent" tool is REQUIRED for subagent invocation
        allowed_tools=["Read", "Grep", "Glob", "Agent"],
        agents={
            "code-reviewer": AgentDefinition(
                description="Expert code reviewer. Use for quality, security, "
                            "and maintainability reviews of any source file.",
                prompt="""You are a code review specialist with expertise in
security, performance, and best practices.

When reviewing code:
- Identify security vulnerabilities
- Check for performance bottlenecks
- Verify adherence to coding standards
- Suggest specific, actionable improvements""",
                tools=["Read", "Grep", "Glob"],  # read-only analyst
                model="sonnet",
            ),
            "test-runner": AgentDefinition(
                description="Test execution specialist. Use to run test suites "
                            "and analyze failures.",
                prompt="""You are a test execution specialist.
Run tests and provide clear analysis of results.""",
                tools=["Bash", "Read", "Grep"],
            ),
        },
    ),
):
    if hasattr(message, "result"):
        print(message.result)
```

### AgentDefinition Fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `description` | string | **Yes** | Auto-delegation trigger  be specific |
| `prompt` | string | **Yes** | Subagent's system prompt and role |
| `tools` | string[] | No | Scoped tool list; omit to inherit all parent tools |
| `model` | `"sonnet"` \| `"opus"` \| `"haiku"` \| `"inherit"` | No | Override model; defaults to main model |

### What Subagents Inherit

| Inherits | Does NOT Inherit |
|---|---|
| Its own `AgentDefinition.prompt` | Parent's conversation history |
| The Agent tool's prompt string | Parent's system prompt |
| Project CLAUDE.md (if `setting_sources=["project"]`) | Parent's tool results |
| Tool definitions (scoped subset) | Skills (TS only; unless in `AgentDefinition.skills`) |

### Explicit vs. Automatic Invocation

```
# Automatic: Claude matches task against subagent descriptions
prompt = "Review this PR for security issues"
 Claude automatically invokes "code-reviewer"

# Explicit: force a specific subagent by name
prompt = "Use the code-reviewer agent to check auth.py"
 Claude directly invokes "code-reviewer"
```

### Dynamic Agent Configuration

```python
def create_reviewer(security_level: str) -> AgentDefinition:
    is_strict = security_level == "strict"
    return AgentDefinition(
        description="Security code reviewer",
        prompt=f"You are a {'strict' if is_strict else 'standard'} security reviewer...",
        tools=["Read", "Grep", "Glob"],
        model="opus" if is_strict else "sonnet",
    )
```

### Parallel Fan-Out Pattern

```
Orchestrator: "Perform a full code review of this PR"
     style-checker subagent   
     security-scanner subagent  parallel
     test-coverage subagent   
               (all complete)
    Orchestrator synthesizes final report
```

### Evaluator-Optimizer Pattern (Flat Hierarchy)

The original Anthropic "Evaluator-Optimizer" pattern must be implemented **flat** under the Agent SDK's one-level constraint:

```
Orchestrator invokes worker subagents
    
Workers return analyses
     (if HIGH risk or low confidence)
Orchestrator invokes evaluator subagent
  (passing worker output in the Agent tool prompt string)
    
Evaluator returns confidence score + issues
     (if confidence < threshold)
Orchestrator re-invokes worker with evaluator feedback
     (max N passes enforced by orchestrator code, not nesting)
Final synthesis
```

### Detecting Subagent Activity

```python
async for message in query(...):
    if hasattr(message, "content") and message.content:
        for block in message.content:
            if getattr(block, "type", None) == "tool_use":
                if block.name in ("Agent", "Task"):  # "Task" for older SDK versions
                    print(f"Subagent invoked: {block.input.get('subagent_type')}")

    if hasattr(message, "parent_tool_use_id") and message.parent_tool_use_id:
        print("  (running inside subagent)")
```

---

## Chapter 4: Hooks

Hooks are callbacks that fire at specific points in the agent lifecycle. They intercept tool calls, observe lifecycle events, and can block, modify, or augment agent behavior  without consuming context window tokens.

### Available Events

| Event | Python/TS | Can Block | Typical Use |
|---|---|---|---|
| `PreToolUse` | Both | **Yes** | Validate inputs, enforce permissions, deny dangerous commands |
| `PostToolUse` | Both | No | Audit logging, trigger side effects |
| `PostToolUseFailure` | Both | No | Error tracking, alerting |
| `UserPromptSubmit` | Both | No | Inject additional context into prompts |
| `Stop` | Both | No | Save session state, cleanup |
| `SubagentStart` | Both | No | Track parallel task spawning |
| `SubagentStop` | Both | No | Aggregate results, measure duration |
| `PreCompact` | Both | No | Archive transcript before summarizing |
| `PermissionRequest` | Both | No | Custom permission UI |
| `Notification` | Both | No | Forward status updates to Slack, PagerDuty |
| `SessionStart` | TS only | No | Initialize telemetry |
| `SessionEnd` | TS only | No | Clean up resources |

### Hook Evaluation Flow

```
Event fires
    
Matchers filter which hooks run (regex against tool name)
    
Callbacks execute in registration order
    
Each returns: {} | deny | allow+updatedInput | systemMessage | async_
    
deny > ask > allow when multiple hooks apply
```

### Registering Hooks

```python
options = ClaudeAgentOptions(
    hooks={
        "PreToolUse": [
            HookMatcher(matcher="Write|Edit", hooks=[protect_env_files]),
            HookMatcher(matcher="^mcp__", hooks=[mcp_audit_hook]),
            HookMatcher(hooks=[global_logger]),  # no matcher = all tools
        ],
        "PostToolUse": [HookMatcher(hooks=[db_audit_hook])],
        "SubagentStop": [HookMatcher(hooks=[aggregate_results_hook])],
        "Stop": [HookMatcher(hooks=[save_session_hook])],
    }
)
```

### Common Hook Patterns

**Block sensitive files:**
```python
async def protect_env_files(input_data, tool_use_id, context):
    file_path = input_data["tool_input"].get("file_path", "")
    if file_path.endswith(".env"):
        return {
            "hookSpecificOutput": {
                "hookEventName": input_data["hook_event_name"],
                "permissionDecision": "deny",
                "permissionDecisionReason": "Cannot modify .env files",
            }
        }
    return {}
```

**Redirect file writes to sandbox:**
```python
async def redirect_to_sandbox(input_data, tool_use_id, context):
    if input_data["tool_name"] == "Write":
        original_path = input_data["tool_input"].get("file_path", "")
        return {
            "hookSpecificOutput": {
                "hookEventName": input_data["hook_event_name"],
                "permissionDecision": "allow",  # required with updatedInput
                "updatedInput": {
                    **input_data["tool_input"],
                    "file_path": f"/sandbox{original_path}",
                },
            }
        }
    return {}
```

**Async fire-and-forget logging:**
```python
async def async_logger(input_data, tool_use_id, context):
    asyncio.create_task(send_to_logging_service(input_data))
    return {"async_": True, "asyncTimeout": 30000}  # agent doesn't wait
```

**Track subagent duration:**
```python
subagent_start_times = {}

async def subagent_tracker(input_data, tool_use_id, context):
    agent_id = input_data.get("agent_id")
    event = input_data["hook_event_name"]
    if event == "SubagentStart":
        subagent_start_times[agent_id] = time.time()
    elif event == "SubagentStop":
        duration = time.time() - subagent_start_times.pop(agent_id, time.time())
        print(f"Subagent {agent_id} completed in {duration:.2f}s")
    return {}
```

### Hook Gotchas

- **Recursive loops:** `UserPromptSubmit` hooks that spawn subagents can recurse infinitely. Check `agent_id` in input before spawning.
- **Subagents don't inherit parent permissions.** Use `PreToolUse` hooks to auto-approve tools for subagents.
- **`SessionStart/SessionEnd`** are TypeScript-only for SDK callback hooks. Python uses shell command hooks via `settings.json`.

---

## Chapter 5: MCP Servers

The Model Context Protocol (MCP) is an open standard for connecting agents to external tools and data sources.

### Tool Naming Convention

All MCP tools follow: `mcp__{server-name}__{tool-name}`

Examples:
- `mcp__github__list_issues`
- `mcp__compliance__check_jurisdiction_rules`
- `mcp__legal-analysis__flag_risk`

Wildcard in `allowedTools`: `"mcp__github__*"` permits all tools from the `github` server.

### Transport Types

| Type | Config Fields | When to Use |
|---|---|---|
| `stdio` | `command`, `args`, `env` | Local process on same machine |
| `http` | `type: "http"`, `url`, `headers` | Remote cloud-hosted, non-streaming |
| `sse` | `type: "sse"`, `url`, `headers` | Remote cloud-hosted, streaming |

### Configuring MCP Servers

```python
options = ClaudeAgentOptions(
    mcp_servers={
        # stdio: local process
        "github": {
            "command": "npx",
            "args": ["-y", "@modelcontextprotocol/server-github"],
            "env": {"GITHUB_TOKEN": os.environ["GITHUB_TOKEN"]}
        },
        # HTTP: remote server
        "compliance-api": {
            "type": "http",
            "url": "https://compliance.example.com/mcp",
            "headers": {"Authorization": f"Bearer {token}"}
        },
    },
    allowed_tools=["mcp__github__list_issues", "mcp__compliance-api__*"],
)
```

### File-Based Configuration (`.mcp.json`)

```json
{
  "mcpServers": {
    "postgres": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-postgres", "postgresql://..."]
    }
  }
}
```

### Custom In-Process Tools (TypeScript)

Define tools directly in code  no separate server process needed:

```typescript
import { query, tool, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";

const analysisServer = createSdkMcpServer({
  name: "legal-analysis",
  version: "1.0.0",
  tools: [
    tool(
      "flag_risk",
      "Flag a legal risk found in the contract",
      {
        clause: z.string(),
        severity: z.enum(["HIGH", "MEDIUM", "LOW"]),
        description: z.string(),
      },
      async (args) => {
        collectedRisks.push(args);
        return { content: [{ type: "text", text: "Risk flagged." }] };
      }
    ),
  ]
});
```

> **Why this matters:** Instead of prompting "return JSON `{risks:[...]}`" and parsing the response (fragile), each output type is a typed tool Claude calls. With `strict: true`, inputs are schema-guaranteed  no malformed JSON, no fallbacks.

### MCP Tool Search

When many MCP tools would consume excessive context:

```python
options = ClaudeAgentOptions(
    env={"ENABLE_TOOL_SEARCH": "auto"},   # activates > 10% context threshold
    # "auto:5" = 5% threshold | "true" = always | "false" = disabled
)
```

**Requirement:** Sonnet 4+ or Opus 4+. Haiku does not support tool search.

### Error Detection

```python
async for message in query(...):
    if message.type == "system" and message.subtype == "init":
        failed = [s for s in message.mcp_servers if s.status != "connected"]
        if failed:
            print(f"Failed servers: {[s.name for s in failed]}")
```

---

## Chapter 6: Permissions

The Agent SDK has a layered permission system. Understanding the evaluation chain is critical for building safe agents.

### The Evaluation Chain

```
Tool call requested
        
        
1. Hooks (PreToolUse)        allow / deny / continue
         if not resolved
        
2. Deny rules                always blocks (even in bypassPermissions)
   (disallowed_tools)
         if not denied
        
3. Permission mode           mode-specific auto-approval
         if not resolved
        
4. Allow rules               pre-approved tool list
   (allowed_tools)
         if not matched
        
5. canUseTool callback       runtime approval, or deny if absent
   (or deny if dontAsk)
```

### Permission Modes

| Mode | Behavior | Use When |
|---|---|---|
| `"default"` | Unmatched  `canUseTool` callback (deny if absent) | Interactive apps with user approval |
| `"acceptEdits"` | Auto-approves `Edit`, `Write`, filesystem ops | Trusted dev workflows |
| `"dontAsk"` (TS only) | Unmatched tools denied; never calls `canUseTool` | Fixed tool surface, headless agents |
| `"bypassPermissions"` | All tools approved (deny rules still apply) | Sandboxed CI, containers only |
| `"plan"` | No tool execution; Claude plans only | Code review before changes |

### Critical Warnings

> **`bypassPermissions` propagates to ALL subagents.** Cannot be overridden per-subagent. Enabling it gives every subagent full system access.

> **`allowedTools` does NOT restrict `bypassPermissions`.** `allowed_tools=["Read"]` + `bypassPermissions` still approves every tool including `Bash`. Use `disallowed_tools` to block specific tools in bypass mode.

### Scoped Commands

```python
allowed_tools=[
    "Bash(npm:*)",          # only npm commands
    "Bash(git:*)",          # only git commands
    "mcp__compliance__*",   # all compliance MCP tools
]
```

---

## Chapter 7: Sessions

A session is the full conversation history the SDK accumulates: prompts, tool calls, results, Claude's reasoning, and responses. Persisted to disk as JSONL; resumable after restarts.

### Session Storage Path

```
~/.claude/projects/<encoded-cwd>/<session-id>.jsonl
```

`<encoded-cwd>` = absolute working directory with non-alphanumeric chars replaced by `-`.

### Choosing the Right Approach

| Scenario | Method |
|---|---|
| Single prompt, no follow-up | Plain `query()`  no session config needed |
| Multi-turn chat, same process | Python: `ClaudeSDKClient`; TS: `continue: true` |
| Resume after process restart | `continue_conversation=True` / `continue: true` |
| Resume a specific session | Capture `session_id`, pass `resume=session_id` |
| Try alternative, keep original | `fork_session=True` |
| Stateless (no disk writes, TS only) | `persistSession: false` |

### Multi-Turn with ClaudeSDKClient (Python)

```python
async with ClaudeSDKClient(options=ClaudeAgentOptions(
    allowed_tools=["Read", "Edit", "Glob"]
)) as client:
    await client.query("Analyze the auth module")
    async for msg in client.receive_response():
        if isinstance(msg, ResultMessage):
            print(msg.result)

    # Automatically continues same session
    await client.query("Now refactor it to use JWT")
    async for msg in client.receive_response():
        if isinstance(msg, ResultMessage):
            print(msg.result)
```

### Capture and Resume by ID

```python
session_id = None
async for message in query(
    prompt="Identify all data protection clauses",
    options=ClaudeAgentOptions(allowed_tools=["Read", "Grep"]),
):
    if isinstance(message, ResultMessage):
        session_id = message.session_id

# Later  resume with full prior context
async for message in query(
    prompt="Draft GDPR-compliant versions of those clauses",
    options=ClaudeAgentOptions(
        resume=session_id,
        allowed_tools=["Read", "Edit", "Write"],
    ),
):
    if isinstance(message, ResultMessage) and message.subtype == "success":
        print(message.result)
```

### Fork for Alternative Exploration

```python
# Fork branches conversation history, not the filesystem
# File edits in a fork are real and visible to all sessions in the same directory
async for message in query(
    prompt="Implement OAuth2 instead of JWT",
    options=ClaudeAgentOptions(
        resume=session_id,
        fork_session=True,    # new session ID; original unchanged
    ),
):
    if isinstance(message, ResultMessage):
        forked_id = message.session_id  # distinct from session_id
```

### Automatic Context Compaction

When context window fills:
- SDK summarizes older history, emits `SystemMessage(subtype="compact_boundary")`
- Instructions from early in the conversation may not survive

> **Put persistent rules in `CLAUDE.md`** (loaded via `setting_sources=["project"]`)  re-injected on every request via prompt caching, survives compaction.

---

## Chapter 8: CLAUDE.md and Project Configuration

When `setting_sources=["project"]` is set, the SDK loads filesystem configuration from `.claude/`  persistent context shared across all sessions.

### CLAUDE.md  Persistent Agent Instructions

```markdown
# CLAUDE.md

## Project Context
This is a legal document management system. Always:
- Treat all document content as confidential
- Add GDPR disclaimers when analyzing EU contracts
- Never suggest edits that reduce data subject rights

## When Compacting History
Preserve: all compliance violations found, risk scores, citation references

## Coding Standards
Use Java 17+, Spring Boot 3.x, JPA for persistence.
```

### Project Structure

```
.claude/
 CLAUDE.md              # persistent agent instructions
 settings.json          # declarative allow/deny rules
 agents/                # filesystem-based agent definitions
    researcher.md
 skills/                # reusable capabilities
    legal-analysis/
        SKILL.md
 commands/              # slash command shortcuts
     review-contract.md
```

---

## Chapter 9: Context Window Management

The context window accumulates across turns within a session and does not reset.

### What Consumes Context

| Content | Notes |
|---|---|
| System prompt | Fixed cost; prompt-cached (low cost after first request) |
| CLAUDE.md | Prompt-cached; put persistent rules here |
| Tool definitions | Each tool adds its schema; prompt-cached |
| Conversation history | Largest cost  grows every turn |
| MCP tool schemas | All tools from all servers loaded upfront unless tool search enabled |

### Strategies for Long-Running Agents

1. **Use subagents for subtasks**  each gets fresh context; parent receives only final summary
2. **Scope subagent `tools`**  every tool definition uses context; minimize the list
3. **Enable MCP tool search**  large tool sets load on-demand
4. **Lower `effort` for routine tasks**  `"low"` for read-only tasks uses fewer reasoning tokens
5. **Limit MCP servers**  each connected server adds all its schemas to every request

---

## Chapter 10: Java Integration via LangChain4J

The Agent SDK is Python/TypeScript native. Java/Spring Boot projects use **LangChain4J 1.12.2+** to implement equivalent patterns.

> **Critical:** LangChain4J 0.25.0 has NO Anthropic provider, NO MCP client, NO tool use. You must upgrade to 1.12.2+.

### Dependencies

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-anthropic</artifactId>
    <version>1.12.2</version>
</dependency>
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-mcp</artifactId>
    <version>1.12.2</version>
</dependency>
```

### AnthropicChatModel with Prompt Caching

```java
AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4-5")
    .temperature(0.0)
    .cacheSystemMessages(true)   // 90% savings on cached system prompts
    .cacheTools(true)            // cache tool definitions
    .build();
```

### Extended Thinking (Evaluator Subagent)

```java
AnthropicChatModel evaluatorModel = AnthropicChatModel.builder()
    .apiKey(apiKey)
    .modelName("claude-opus-4")
    .thinkingType("enabled")
    .thinkingBudgetTokens(10000)  // billed at full input rate; restrict to HIGH-risk docs
    .build();
```

### Tool Use via AiServices (Replaces JSON Parsing)

```java
interface ContractAnalyzer {
    @Tool("Flag a legal risk found in the contract")
    void flagRisk(
        @P("clause") String clause,
        @P("severity") String severity,  // HIGH | MEDIUM | LOW
        @P("description") String description
    );

    @Tool("Note an ambiguous clause requiring clarification")
    void noteAmbiguity(
        @P("clause") String clause,
        @P("explanation") String explanation
    );

    @Tool("Suggest an improvement to a contract clause")
    void suggestEdit(
        @P("clause") String clause,
        @P("suggestion") String suggestion
    );
}
```

This replaces the "return JSON" prompt pattern entirely. No `extractJson()`, no fallbacks.

### MCP Client (Java)

```java
// stdio transport  local MCP server process
McpTransport transport = new StdioMcpTransport.Builder()
    .command("python", "compliance_mcp_server.py")
    .logEvents(true)
    .build();

McpClient mcpClient = new DefaultMcpClient.Builder()
    .transport(transport)
    .build();

McpToolProvider toolProvider = McpToolProvider.builder()
    .mcpClients(mcpClient)
    .build();
```

### SSE Streaming (Replaces Blocking CompletableFuture)

```java
@GetMapping(value = "/{id}/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter analyzeStreaming(@PathVariable Long id) {
    SseEmitter emitter = new SseEmitter(180_000L);

    AnthropicStreamingChatModel streamingModel = AnthropicStreamingChatModel.builder()
        .apiKey(apiKey)
        .modelName("claude-sonnet-4-5")
        .build();

    streamingModel.generate(messages, new StreamingResponseHandler<>() {
        public void onNext(String token) {
            try { emitter.send(token); } catch (IOException e) { emitter.completeWithError(e); }
        }
        public void onComplete(Response<AiMessage> r) { emitter.complete(); }
        public void onError(Throwable t) { emitter.completeWithError(t); }
    });

    return emitter;  // returns immediately; tokens stream to client
}
```

### Implementing Agent SDK Patterns in Java

Since the Agent SDK is Python/TS native, Java projects implement its architectural patterns manually:

**AgentDefinition:**
```java
public record AgentDefinition(
    String name,
    String description,
    String promptTemplate,
    List<String> tools,
    String model
) {}
```

**HookRegistry (replaces AOP):**
```java
public class HookRegistry {
    private final Map<HookEvent, List<HookMatcher>> hooks = new HashMap<>();

    public void on(HookEvent event, HookMatcher matcher) {
        hooks.computeIfAbsent(event, k -> new ArrayList<>()).add(matcher);
    }

    public HookOutput dispatch(HookEvent event, HookInput input) {
        return hooks.getOrDefault(event, List.of()).stream()
            .filter(m -> m.matches(input.toolName()))
            .map(m -> m.execute(input))
            .reduce(HookOutput.allow(), HookOutput::merge);
    }
}
```

**Registration:**
```java
HookRegistry registry = HookRegistry.builder()
    .on(PRE_TOOL_USE,  HookMatcher.all(new PermissionHook(roleService)))
    .on(PRE_TOOL_USE,  HookMatcher.of("^mcp__legal-analysis__", new PiiScanHook()))
    .on(POST_TOOL_USE, HookMatcher.all(new AuditHook(auditRepository)))
    .on(STOP,          HookMatcher.all(new SessionSaveHook(documentRepository)))
    .build();
```

### Prompt Caching Economics

For compliance checks where system prompt + rules  2000 tokens:

| Request | Tokens Charged | Multiplier |
|---|---|---|
| First call (cache write) | 2000  1.25 = 2500 | 1.25 base |
| Subsequent (cache hit) | 2000  0.10 = 200 | **0.10 base** |

Over 100 documents/day in the same jurisdiction: **saves ~180K cached input tokens/day**.

Batch API (50% discount) stacks on top of caching for overnight document libraries.

---

## Chapter 11: Real-World Architecture  Legal AI Agent

Applying all concepts to redesign a Java Legal AI Agent from a monolithic GPT-4o service to a Claude Agent SDK-aligned multi-agent system.

### Before: Monolithic Service

```
POST /docs/{id}/analyze
     LegalAiService.analyzeContract()    single LLM call
         "Analyze this. Return JSON: {risks:[], ambiguities:[], edits:[]}"
         extractJson(response)            fragile; fails on prose-wrapped JSON
         createFallbackResult()           silently returns riskLevel="MEDIUM"
         cache(badResult, 30min)          wrong answer cached for 30 minutes
```

### After: Claude Agent SDK Architecture

```
POST /docs/{id}/analyze       returns {sessionId} immediately (202 Accepted)
    
LegalOrchestratorAgent receives document + jurisdiction
    
Auto-delegates (parallel):
     contract-analyst: flagRisk(), noteAmbiguity(), suggestEdit()  
     compliance-checker: autonomous MCP tool loop                     parallel
     legal-researcher: vectorSearch()  citations enabled           
               (waits for contract-analyst result)
    risk-scorer: scoreCategory(), addCriticalIssue()
               (if HIGH risk)
    evaluator:  validateCitation(), checkConsistency()   Extended Thinking
              
    Orchestrator synthesizes final report
    
SessionSaveHook persists result to Document JSONB
    
GET /sessions/{sessionId}     poll for result
```

### Subagent Definitions

| Name | Description (triggers auto-delegation) | Tools | Model |
|---|---|---|---|
| `contract-analyst` | "Contract analysis specialist. Use for identifying risks, ambiguities, and suggesting edits." | `mcp__legal-analysis__flagRisk`, `noteAmbiguity`, `suggestEdit` | sonnet |
| `risk-scorer` | "Risk scoring specialist. Use for quantitative risk assessment." | `mcp__legal-analysis__scoreCategory`, `addCriticalIssue` | sonnet |
| `compliance-checker` | "Compliance verification specialist. Use for jurisdiction-specific regulation checks." | `mcp__compliance__*` | sonnet |
| `legal-researcher` | "Legal research specialist. Use for finding case law, statutes, and precedents." | `mcp__research__vectorSearch`, `citeCaseLaw`, `citeStatute` | sonnet |
| `evaluator` | "Quality assurance evaluator. Use after analysis to verify accuracy and check for hallucinations." | `mcp__legal-analysis__validateCitation`, `checkConsistency` (read-only) | opus |

### Compliance Checker Autonomous Tool Loop

```
compliance-checker subagent receives: document + "US-CA"
    
Think: "California contract  CCPA applies."
Call: mcp__compliance__check_jurisdiction_rules(text, "US-CA")
     3 violations found
Think: "Check required provisions."
Call: mcp__compliance__check_required_clauses(text, "US-CA")
     missing: privacy notice, opt-out mechanism
Think: "Scan for exposed PII."
Call: mcp__compliance__check_data_protection(text)
     2 emails, 1 SSN found unredacted
Think: "All findings collected. Compile report."
Return: structured ComplianceResult (final message to orchestrator)
```

### Hook-Based Audit (Replaces AOP)

```java
// Replaces @AfterReturning + @AfterThrowing on every service method
HookRegistry registry = HookRegistry.builder()
    .on(POST_TOOL_USE,         HookMatcher.all(new AuditHook(auditRepo)))
    .on(POST_TOOL_USE_FAILURE, HookMatcher.all(new ErrorAuditHook(auditRepo)))
    .on(PRE_TOOL_USE,          HookMatcher.all(new PermissionHook(roleService)))
    .on(PRE_TOOL_USE,          HookMatcher.of("^mcp__legal-analysis__", new PiiScanHook()))
    .build();
```

### Legal Research with Claude Citations

```python
async def cite_case_law(query: str, jurisdiction: str) -> dict:
    chunks = vector_store.search(query, jurisdiction, top_k=10)

    response = client.messages.create(
        model="claude-sonnet-4-5",
        messages=[{
            "role": "user",
            "content": [
                {
                    "type": "document",
                    "source": {"type": "text", "data": chunk.text},
                    "title": chunk.title,
                    "citations": {"enabled": True}
                }
                for chunk in chunks
            ] + [{"type": "text", "text": f"Find citations for: {query}"}]
        }]
    )
    # Response includes structured citations pointing to exact char positions
    # Cited text does NOT count as output tokens (cost saving)
    return response
```

---

## Chapter 12: Key Constraints and Gotchas

### Architectural

| Constraint | Detail |
|---|---|
| **Subagents cannot nest** | Flat hierarchy only. Max 1 level below orchestrator. Never `Agent` in subagent `tools`. |
| **Subagents don't inherit parent context** | Pass everything needed via Agent tool prompt string. |
| **Citations + Tool Use incompatible** | Claude returns HTTP 400 if both enabled. Route: tool use for analysis, citations for research. |
| **Extended Thinking + temperature/top_k** | Incompatible. Cannot set temperature with Extended Thinking enabled. |
| **Extended Thinking + forced tool use** | Incompatible. |

### Permission

| Constraint | Detail |
|---|---|
| **`bypassPermissions` propagates** | All subagents inherit it. Cannot be overridden per-subagent. |
| **`allowedTools` doesn't restrict `bypassPermissions`** | Use `disallowed_tools` to block in bypass mode. |
| **`dontAsk` mode** | TypeScript SDK only. No Python equivalent. |
| **Subagents don't inherit parent permissions** | Each subagent triggers its own permission checks. |

### SDK-Specific

| Constraint | Detail |
|---|---|
| **Custom in-process tools need streaming input (TS)** | Must use async generator for `prompt`  plain string won't work. |
| **`SessionStart/SessionEnd` hooks** | TypeScript SDK only. Python uses shell command hooks. |
| **MCP tool search model requirement** | Sonnet 4+ or Opus 4+. Haiku not supported. |
| **Windows subagent limit** | Long prompts fail at 8191 char CLI limit. Use filesystem-defined agents. |
| **Resume requires matching `cwd`** | Different directory = SDK looks in wrong place, creates fresh session. |
| **Recursive hook loops** | `UserPromptSubmit` hooks spawning subagents can infinite-loop. Check `agent_id` first. |

### Cost

| Constraint | Detail |
|---|---|
| **Extended Thinking billing** | Thinking tokens billed at full input rate. 10K budget  $0.03/evaluation. |
| **Prompt caching write overhead** | First request charged 1.25 base rate. Paid back after 2+ cache hits. |
| **No limits in `bypassPermissions`** | Without `max_turns`/`max_budget_usd`, runaway agents can act indefinitely. Always set limits in production. |

---

## Conclusion

The Claude Agent SDK represents a shift from request-response AI calls embedded in application code to autonomous agents that use tools, maintain state, spawn specialized sub-workers, and are governed by a coherent permission model.

**Key architectural principles:**

1. **Let Claude handle the loop.** Give Claude tools and let it iterate  don't manually orchestrate prompt  parse  act chains.
2. **Subagents for everything big.** Context isolation + parallelization make complex workflows tractable. Keep each subagent's tool surface minimal.
3. **Hooks over AOP.** Lifecycle hooks with typed events are more predictable than aspect-oriented interception  and they let you modify agent behavior, not just observe it.
4. **MCP for all tools.** Uniform naming, permission management, lazy loading, and error detection through a single protocol.
5. **CLAUDE.md for persistent rules.** The initial prompt gets compacted. Persistent instructions belong in CLAUDE.md  re-injected on every request via prompt caching.
6. **Session IDs for continuity.** Always capture and store session IDs. Users should resume analyses, fork explorations, and pick up where they left off.
7. **Citations for research, Tool Use for analysis.** These features are incompatible combined. Route to the right mode based on task type.

These principles apply whether you use the Python/TypeScript Agent SDK directly or implement equivalent patterns in Java via LangChain4J 1.12.2.

---

*Sources:*
- *Claude Agent SDK: https://platform.claude.com/docs/en/api/agent-sdk/overview*
- *LangChain4J: https://docs.langchain4j.dev/*
- *MCP Specification: https://modelcontextprotocol.io/*
