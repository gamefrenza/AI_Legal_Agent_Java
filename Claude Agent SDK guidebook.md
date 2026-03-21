```markdown
# Building Autonomous AI Agents with the Claude Agent SDK

## A Comprehensive Developer's Guide

---

## Introduction

Anthropic's **Claude Agent SDK** (formerly the Claude Code SDK) is a Python and TypeScript library that gives you programmatic access to the same autonomous agent loop that powers Claude Code. Rather than sending a prompt and parsing a single response, the Agent SDK lets Claude autonomously call tools, observe results, adjust its approach, and iterate — completing multi-step tasks without manual orchestration.

The fundamental difference from the standard Anthropic Client SDK:

```python
# Client SDK: YOU implement the tool loop
response = client.messages.create(...)
while response.stop_reason == "tool_use":
    result = your_tool_executor(response.tool_use)
    response = client.messages.create(tool_result=result, **params)

# Agent SDK: Claude handles tools autonomously
async for message in query(prompt="Fix the bug in auth.py"):
    print(message)  # Claude reads, reasons, edits — you just watch
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
   → SDK yields SystemMessage(subtype="init") with session metadata

2. Claude evaluates and responds
   → SDK yields AssistantMessage with text + any tool call requests

3. SDK executes tools
   → Hooks run first (can block/modify)
   → SDK yields UserMessage with tool results fed back to Claude

4. Repeat steps 2-3
   → Each round trip = one "turn"
   → Continues until Claude produces output with no tool calls

5. Return result
   → SDK yields final AssistantMessage (text only)
   → SDK yields ResultMessage (always last) with final text, cost, usage, session_id
```

### Message Types

| Type | When | Key Fields |
|---|---|---|
| `SystemMessage` | Session start; after compaction | `subtype: "init"` or `"compact_boundary"` |
| `AssistantMessage` | After each Claude response | `content[]` — text blocks + tool use blocks |
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

`result` is only present on `success`. Always check `subtype` first. All subtypes carry `total_cost_usd`, `session_id`, and `num_turns` — track cost and resume even after errors.

### Loop Controls

All set via `ClaudeAgentOptions` (Python) or the `options` object (TypeScript):

```python
options = ClaudeAgentOptions(
    max_turns=30,           # cap tool-use round trips; default = no limit
    max_budget_usd=0.50,    # cost cap; default = no limit
    effort="high",          # "low" | "medium" | "high" | "max" — reasoning depth
    model="claude-sonnet-4-5",  # pin specific model
)
```

**Effort levels:**

| Level | Reasoning | Tokens | Use When |
|---|---|---|---|
| `"low"` | Minimal | Fewest | File lookups, listing directories |
| `"medium"` | Balanced | Moderate | Routine edits, standard tasks |
| `"high"` | Thorough | More | Refactors, debugging |
| `"max"` | Maximum depth | Most | Multi-step problems requiring deep analysis |

**Note:** `effort` is independent of Extended Thinking. `effort` controls reasoning depth within a single response; Extended Thinking generates visible chain-of-thought blocks. You can combine them freely.

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

The Agent SDK ships with Claude Code's full tool set. No implementation required — the SDK handles execution.

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
| Agent | Orchestration | Spawn subagents (required to use subagents) |
| `Skill` | Orchestration | Invoke filesystem-defined skills |
| `AskUserQuestion` | Interaction | Ask clarifying questions with multiple-choice options |
| `TodoWrite` | Tracking | Maintain a task list within the session |
| `ToolSearch` | Discovery | Dynamically load MCP tools on-demand (large tool sets) |

### Parallel vs. Sequential Execution

When Claude requests multiple tool calls in one turn:
- **Read-only tools** (`Read`, `Glob`, `Grep`, read-only MCP tools) → run **concurrently**
- **State-modifying tools** (`Edit`, `Write`, `Bash`) → run **sequentially** to avoid conflicts
- **Custom tools** → sequential by default; set `readOnlyHint=True` (Python) to enable parallel

### Controlling Tool Access

```python
options = ClaudeAgentOptions(
    # Pre-approve these tools (no permission prompt required)
    allowed_tools=["Read", "Glob", "Grep", "Edit"],

    # Block these tools entirely — enforced even in bypassPermissions mode
    disallowed_tools=["Bash"],
)
```

Scoped rules: `"Bash(npm:*)"` allows only npm commands via Bash. See the Permissions chapter for full rule syntax.

---

## Chapter 3: Subagents

Subagents are the SDK's mechanism for context isolation and parallelization. The main agent spawns subagents to handle focused subtasks; each runs in its own fresh conversation.

### Why Use Subagents

**Context isolation:** A `research-assistant` subagent can explore dozens of files without any of that content accumulating in the main agent's context window. The parent receives a concise summary — not every file the subagent read.

**Parallelization:** Multiple subagents run concurrently. A code review that would take 3 minutes sequentially (style + security + test coverage) takes 1 minute with parallel subagents.

**Specialization:** Each subagent gets tailored system prompts with domain-specific expertise and minimal tool access, reducing the risk of unintended actions.

### Hard Constraints

These constraints are architectural — they cannot be worked around:

1. **Subagents cannot nest.** The hierarchy is flat: orchestrator → subagents. A subagent cannot spawn its own subagents. Never include Agent in a subagent's `tools` list.
2. **Isolated context.** The subagent does NOT see the parent's conversation history or tool results. The only channel from parent to subagent is the Agent tool's prompt string.
3. **Only the final message returns.** All intermediate tool calls stay inside the subagent's isolated session. The parent's context grows by one summary, not by the subagent's full transcript.
4. **Claude auto-delegates based on `description`.** Write clear, specific descriptions — that's what Claude matches against to decide which subagent to invoke.

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
                # Claude reads this to decide when to invoke the subagent
                description="Expert code reviewer. Use for quality, security, "
                            "and maintainability reviews of any source file.",
                # The subagent's system prompt
                prompt="""You are a code review specialist with expertise in
security, performance, and best practices.

When reviewing code:
- Identify security vulnerabilities
- Check for performance bottlenecks
- Verify adherence to coding standards
- Suggest specific, actionable improvements

Be thorough but concise.""",
                # Restrict tools — read-only analyst
                tools=["Read", "Grep", "Glob"],
                # Override model for this subagent
                model="sonnet",
            ),
            "test-runner": AgentDefinition(
                description="Test execution specialist. Use to run test suites "
                            "and analyze failures.",
                prompt="""You are a test execution specialist.
Run tests and provide clear analysis of results.
- Execute test commands
- Identify failing tests and root causes
- Suggest minimal fixes for failures""",
                # Needs Bash to run tests
                tools=["Bash", "Read", "Grep"],
                # No model override — inherits main model
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
| `description` | string | **Yes** | Auto-delegation trigger — be specific |
| `prompt` | string | **Yes** | Subagent's system prompt and role |
| `tools` | string[] | No | Scoped tool list; omit to inherit all parent tools |
| `model` | `"sonnet"` \| `"opus"` \| `"haiku"` \| `"inherit"` | No | Override model; defaults to main model |

### What Subagents Inherit

| Inherits | Does NOT Inherit |
|---|---|
| Its own `AgentDefinition.prompt` | Parent's conversation history |
| The Agent tool's prompt string | Parent's system prompt |
| Project CLAUDE.md (if `setting_sources=["project"]`) | Parent's tool results |
| Tool definitions (scoped subset) | Skills (TS only; unless listed in `AgentDefinition.skills`) |

**Implication:** If a subagent needs context from the parent (a file path, a prior finding, a decision), include it explicitly in the Agent tool's prompt string when invoking the subagent.

### Explicit vs. Automatic Invocation

```
# Automatic: Claude matches task description against subagent descriptions
prompt = "Review this PR for security issues"
→ Claude automatically invokes "code-reviewer"

# Explicit: force a specific subagent
prompt = "Use the code-reviewer agent to check auth.py"
→ Claude directly invokes "code-reviewer" regardless of description matching
```

### Dynamic Agent Configuration

Create `AgentDefinition` objects at runtime based on conditions:

```python
def create_reviewer(security_level: str) -> AgentDefinition:
    is_strict = security_level == "strict"
    return AgentDefinition(
        description="Security code reviewer",
        prompt=f"You are a {'strict' if is_strict else 'standard'} security reviewer...",
        tools=["Read", "Grep", "Glob"],
        # More capable model for high-stakes strict reviews
        model="opus" if is_strict else "sonnet",
    )

options = ClaudeAgentOptions(
    allowed_tools=["Read", "Grep", "Glob", "Agent"],
    agents={"security-reviewer": create_reviewer("strict")},
)
```

### Parallel Fan-Out Pattern

```
Orchestrator prompt: "Perform a full code review of this PR"
    │
    ├─ style-checker subagent   ─┐
    ├─ security-scanner subagent ─┤ parallel
    └─ test-coverage subagent   ─┘
                │ (all complete)
    Orchestrator synthesizes final report
```

### Evaluator-Optimizer Pattern (Flat)

The original Anthropic "Evaluator-Optimizer" pattern (generator → evaluator → revise → loop) must be implemented **flat** in the Agent SDK:

```
Orchestrator prompts worker subagents
    │
    ↓
Worker subagents return analyses
    │
    ↓ (if HIGH risk or low confidence)
Orchestrator invokes evaluator subagent
  (passing worker output in the Agent tool prompt string)
    │
    ↓
Evaluator returns confidence score + issues list
    │
    ↓ (if confidence < threshold)
Orchestrator re-invokes relevant worker subagent
  WITH evaluator feedback in the prompt string
    │
    ↓ (max N passes enforced by orchestrator code)
Final synthesis
```

No nesting — the orchestrator manages the retry loop, not the subagents.

### Detecting Subagent Activity

```python
async for message in query(...):
    if hasattr(message, "content") and message.content:
        for block in message.content:
            if getattr(block, "type", None) == "tool_use":
                # Check both names for compatibility across SDK versions
                if block.name in ("Agent", "Task"):
                    print(f"Subagent invoked: {block.input.get('subagent_type')}")

    # Messages from within a subagent's context
    if hasattr(message, "parent_tool_use_id") and message.parent_tool_use_id:
        print("  (running inside subagent)")
```

### Resuming Subagents

Subagents can be resumed to continue where they left off:
1. Capture `session_id` from `ResultMessage`
2. Extract `agentId` from message content (appears in Agent tool results)
3. Pass `resume=session_id` in next `query()` + include agent ID in prompt

---

## Chapter 4: Hooks

Hooks are callbacks that fire at specific points in the agent lifecycle. They intercept tool calls, observe lifecycle events, and can block, modify, or augment agent behavior — without consuming context window tokens.

### Available Events

| Event | Python/TS | Can Block | Typical Use |
|---|---|---|---|
| `PreToolUse` | Both | **Yes** | Validate inputs, enforce permissions, deny dangerous commands |
| `PostToolUse` | Both | No | Audit logging, trigger side effects, append context |
| `PostToolUseFailure` | Both | No | Error tracking, alerting |
| `UserPromptSubmit` | Both | No | Inject additional context into prompts |
| `Stop` | Both | No | Save session state, cleanup, final validation |
| `SubagentStart` | Both | No | Track parallel task spawning, initialize metrics |
| `SubagentStop` | Both | No | Aggregate results, measure duration |
| `PreCompact` | Both | No | Archive full transcript before summarizing |
| `PermissionRequest` | Both | No | Custom permission UI |
| `Notification` | Both | No | Forward status updates to Slack, PagerDuty, etc. |
| `SessionStart` | TS only | No | Initialize telemetry and logging |
| `SessionEnd` | TS only | No | Clean up temporary resources |

### How Hooks Work

```
Event fires (tool about to be called, subagent started, etc.)
    ↓
SDK collects registered hooks for that event type
    ↓
Matcher patterns filter which hooks run (regex against tool name)
    ↓
Callback functions execute in registration order
    ↓
Each callback returns a decision:
  - {} → allow without changes
  - {hookSpecificOutput: {permissionDecision: "deny"}} → block the operation
  - {hookSpecificOutput: {permissionDecision: "allow", updatedInput: {...}}} → modify + allow
  - {systemMessage: "..."} → inject guidance model sees
  - {async_: True} → fire-and-forget (agent doesn't wait)
```

**Multi-hook precedence:** deny > ask > allow. If any hook returns `deny`, the operation is blocked regardless of other hooks.

### Registering Hooks

```python
from claude_agent_sdk import ClaudeSDKClient, ClaudeAgentOptions, HookMatcher

options = ClaudeAgentOptions(
    hooks={
        "PreToolUse": [
            # Protect .env files from modification
            HookMatcher(matcher="Write|Edit", hooks=[protect_env_files]),
            # Audit all MCP tool calls
            HookMatcher(matcher="^mcp__", hooks=[mcp_audit_hook]),
            # Log every tool call
            HookMatcher(hooks=[global_logger]),  # no matcher = matches all
        ],
        "PostToolUse": [
            HookMatcher(hooks=[db_audit_hook]),
        ],
        "SubagentStop": [
            HookMatcher(hooks=[aggregate_results_hook]),
        ],
        "Stop": [
            HookMatcher(hooks=[save_session_hook]),
        ],
    }
)
```

### Callback Signatures

```python
async def my_hook(input_data: dict, tool_use_id: str | None, context) -> dict:
    # input_data keys always present:
    #   session_id, cwd, hook_event_name
    # PreToolUse adds:
    #   tool_name, tool_input, agent_id (if inside subagent), agent_type
    # PostToolUse adds:
    #   tool_name, tool_input, tool_response
    # SubagentStop adds:
    #   agent_id, agent_transcript_path, stop_hook_active
    return {}
```

### Common Hook Patterns

**Block sensitive file modifications:**
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

**Modify tool input (sandboxing):**
```python
async def redirect_to_sandbox(input_data, tool_use_id, context):
    if input_data["tool_name"] == "Write":
        original_path = input_data["tool_input"].get("file_path", "")
        return {
            "hookSpecificOutput": {
                "hookEventName": input_data["hook_event_name"],
                "permissionDecision": "allow",  # required when using updatedInput
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
async def async_webhook_logger(input_data, tool_use_id, context):
    asyncio.create_task(send_to_logging_service(input_data))
    return {"async_": True, "asyncTimeout": 30000}  # agent doesn't wait
```

**Forward notifications to Slack:**
```python
async def slack_notifier(input_data, tool_use_id, context):
    # Fires for: permission_prompt, idle_prompt, auth_success, elicitation_dialog
    message = input_data.get("message", "")
    await asyncio.to_thread(send_slack_message, f"Agent status: {message}")
    return {}
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
        print(f"Transcript: {input_data.get('agent_transcript_path')}")
    return {}
```

### Hook Gotchas

- **Recursive loops:** `UserPromptSubmit` hooks that spawn subagents can recurse infinitely. Check for subagent context (`agent_id` in input) before spawning.
- **Subagents don't inherit parent permissions.** Each subagent may trigger permission prompts separately. Use `PreToolUse` hooks to auto-approve tools for subagents.
- **`systemMessage` doesn't appear in all output modes.** Log separately if your application needs to surface hook decisions.
- **`SessionStart/SessionEnd`** are TypeScript-only for SDK callback hooks. In Python, use shell command hooks via `settings.json` and `setting_sources=["project"]`.

---

## Chapter 5: MCP Servers

The Model Context Protocol (MCP) is an open standard for connecting agents to external tools and data sources. The Agent SDK treats all tools — both built-in and external — as MCP-compatible.

### Tool Naming Convention

All MCP tools follow a strict pattern:

```
mcp__{server-name}__{tool-name}
```

Examples:
- `mcp__github__list_issues`
- `mcp__postgres__query`
- `mcp__compliance__check_jurisdiction_rules`

Wildcard in `allowedTools`: `"mcp__github__*"` permits all tools from the `github` server.

### Transport Types

| Type | Config Fields | When to Use |
|---|---|---|
| `stdio` | `command`, `args`, `env` | Local process on same machine (default for most MCP servers) |
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
        # SSE: streaming remote server
        "analytics": {
            "type": "sse",
            "url": "https://analytics.example.com/mcp/sse",
            "headers": {"X-API-Key": os.environ["ANALYTICS_KEY"]}
        },
    },
    allowed_tools=[
        "mcp__github__list_issues",
        "mcp__github__create_issue",
        "mcp__compliance-api__*",         # wildcard for all compliance tools
        "mcp__analytics__get_metrics",
    ],
)
```

### File-Based Configuration

Create `.mcp.json` at your project root — SDK loads it automatically:

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

Define tools directly in code rather than running a separate server process:

```typescript
import { query, tool, createSdkMcpServer } from "@anthropic-ai/claude-agent-sdk";
import { z } from "zod";

const legalAnalysisServer = createSdkMcpServer({
  name: "legal-analysis",
  version: "1.0.0",
  tools: [
    tool(
      "flag_risk",
      "Flag a legal risk found in the contract document",
      {
        clause: z.string().describe("The exact clause text"),
        severity: z.enum(["HIGH", "MEDIUM", "LOW"]).describe("Risk severity"),
        description: z.string().describe("Explanation of the risk"),
      },
      async (args) => {
        // Store the structured risk finding
        collectedRisks.push(args);
        return { content: [{ type: "text", text: "Risk flagged." }] };
      }
    ),
    tool(
      "suggest_edit",
      "Suggest an improvement to a contract clause",
      {
        clause: z.string(),
        suggestion: z.string(),
      },
      async (args) => {
        collectedEdits.push(args);
        return { content: [{ type: "text", text: "Edit suggestion recorded." }] };
      }
    ),
  ]
});

// Use with streaming input (required for in-process MCP tools)
async function* generateMessages() {
  yield {
    type: "user" as const,
    message: { role: "user" as const, content: "Analyze this contract..." }
  };
}

for await (const message of query({
  prompt: generateMessages(),
  options: {
    mcpServers: { "legal-analysis": legalAnalysisServer },
    allowedTools: ["mcp__legal-analysis__flag_risk", "mcp__legal-analysis__suggest_edit"],
  },
})) { ... }
```

**Why this matters:** Instead of prompting "return JSON like `{risks: [...]}"`" and parsing the response (fragile), you define each output type as a tool Claude calls. With `strict: true`, inputs are schema-guaranteed — no malformed JSON, no fallbacks needed.

### MCP Tool Search

When you have many MCP tools, their definitions consume significant context. Tool search loads tools on-demand instead of preloading all:

```python
options = ClaudeAgentOptions(
    mcp_servers={...},
    env={"ENABLE_TOOL_SEARCH": "auto"},   # activates > 10% context threshold
    # or "auto:5" for 5% threshold, "true" always, "false" disabled
)
```

**Requirement:** Sonnet 4+ or Opus 4+ models. Haiku does not support tool search.

### Error Detection

```python
async for message in query(...):
    if message.type == "system" and message.subtype == "init":
        failed = [s for s in message.mcp_servers if s.status != "connected"]
        if failed:
            print(f"Failed to connect: {[s.name for s in failed]}")
            # Handle gracefully — don't wait for agent to fail on missing tools
```

---

## Chapter 6: Permissions

The Agent SDK has a layered permission system that determines whether a tool call is allowed to execute. Understanding the evaluation chain is critical for building safe agents.

### The Evaluation Chain

```
Tool call requested by Claude
        │
        ▼  (checked in this exact order)
┌─────────────────────────────────┐
│  1. Hooks (PreToolUse)          │  → allow / deny / continue to next step
│     Run registered hook callbacks│
└──────────────┬──────────────────┘
               │ if not resolved
               ▼
┌─────────────────────────────────┐
│  2. Deny rules                  │  → always blocks, even in bypassPermissions
│     Check disallowed_tools list  │
└──────────────┬──────────────────┘
               │ if not denied
               ▼
┌─────────────────────────────────┐
│  3. Permission mode             │  → mode-specific auto-approval
│     bypassPermissions / acceptEdits│
└──────────────┬──────────────────┘
               │ if not resolved
               ▼
┌─────────────────────────────────┐
│  4. Allow rules                 │  → pre-approved tool list
│     Check allowed_tools list     │
└──────────────┬──────────────────┘
               │ if not matched
               ▼
┌─────────────────────────────────┐
│  5. canUseTool callback         │  → runtime approval UI, or deny if absent
│     (or deny if dontAsk mode)    │
└─────────────────────────────────┘
```

### Permission Modes

| Mode | Behavior | Use When |
|---|---|---|
| `"default"` | Unmatched tools → `canUseTool` callback (deny if absent) | Interactive applications with user approval |
| `"acceptEdits"` | Auto-approves: `Edit`, `Write`, `mkdir`, `touch`, `rm`, `mv`, `cp` | Trusted dev workflows; file edits without prompting |
| `"dontAsk"` (TS only) | Unmatched tools denied; never calls `canUseTool` | Fixed, explicit tool surface for headless agents |
| `"bypassPermissions"` | All tools approved (but deny rules still apply) | Sandboxed CI, containers, fully trusted environments |
| `"plan"` | No tool execution; Claude plans only | Code review / approval before changes |

```python
options = ClaudeAgentOptions(
    permission_mode="acceptEdits",   # auto-approve file operations
    allowed_tools=["Read", "Edit", "Glob"],  # also pre-approve these
    disallowed_tools=["Bash"],               # always block, regardless of mode
)
```

### Critical Warnings

**`bypassPermissions` propagates to ALL subagents.** It cannot be overridden per-subagent. Enabling it for the orchestrator gives every subagent full system access.

**`allowedTools` does NOT restrict `bypassPermissions`.** Setting `allowed_tools=["Read"]` alongside `bypassPermissions` still approves every tool including `Bash`, `Write`, and `Edit`. Use `disallowed_tools` to block specific tools in bypass mode.

### Scoped Commands

Restrict individual tools to specific operations:

```python
allowed_tools=[
    "Bash(npm:*)",          # only npm commands
    "Bash(git:*)",          # only git commands
    "Read",                 # all file reads
    "mcp__compliance__*",   # all compliance MCP tools
]
```

### Declarative Rules in settings.json

```json
// .claude/settings.json
{
  "permissions": {
    "allow": ["Read", "Glob", "Grep"],
    "deny": ["Bash(rm:*)"]
  }
}
```

Load with `setting_sources=["project"]` in your options.

---

## Chapter 7: Sessions

A session is the full conversation history the SDK accumulates during an agent run: prompts, tool calls, tool results, Claude's reasoning, and responses. Sessions are persisted to disk as JSONL files and can be resumed after restarts, forked for alternative exploration, or enumerated for custom UI.

### Session Storage

```
~/.claude/projects/<encoded-cwd>/<session-id>.jsonl
```

`<encoded-cwd>` is the absolute working directory with every non-alphanumeric character replaced by `-`. Sessions are machine-local by default.

### When to Use Each Approach

| Scenario | Method |
|---|---|
| Single prompt, no follow-up | Plain `query()` — no session config needed |
| Multi-turn chat, same process | Python: `ClaudeSDKClient`; TypeScript: `continue: true` |
| Resume after process restart | `continue_conversation=True` / `continue: true` — most recent session |
| Resume a specific past session | Capture `session_id`, pass `resume=session_id` |
| Try alternative without losing original | `fork_session=True` — creates new session from copy |
| Stateless / no disk writes (TS only) | `persistSession: false` |

### Multi-Turn with ClaudeSDKClient (Python)

```python
from claude_agent_sdk import ClaudeSDKClient, ClaudeAgentOptions, ResultMessage

async with ClaudeSDKClient(options=ClaudeAgentOptions(
    allowed_tools=["Read", "Edit", "Glob"]
)) as client:
    # First turn
    await client.query("Analyze the auth module")
    async for message in client.receive_response():
        if isinstance(message, ResultMessage) and message.subtype == "success":
            print(message.result)

    # Second turn — automatically continues same session
    await client.query("Now refactor it to use JWT")
    async for message in client.receive_response():
        if isinstance(message, ResultMessage) and message.subtype == "success":
            print(message.result)
```

### Capture and Resume by ID

```python
# First run — capture session ID
session_id = None
async for message in query(
    prompt="Analyze the contract and identify all data protection clauses",
    options=ClaudeAgentOptions(allowed_tools=["Read", "Grep"]),
):
    if isinstance(message, ResultMessage):
        session_id = message.session_id
        print(f"Session: {session_id}")

# Later run — resume with full prior context
async for message in query(
    prompt="Now draft amended versions of those clauses for GDPR compliance",
    options=ClaudeAgentOptions(
        resume=session_id,
        allowed_tools=["Read", "Edit", "Write", "Grep"],
    ),
):
    if isinstance(message, ResultMessage) and message.subtype == "success":
        print(message.result)
```

### Fork for Alternative Exploration

```python
# Fork branches the conversation, not the filesystem
# File edits in a forked session are real and visible to all sessions in the same directory
forked_id = None
async for message in query(
    prompt="Instead of JWT, implement OAuth2 for the auth module",
    options=ClaudeAgentOptions(
        resume=session_id,    # start from the analysis session
        fork_session=True,    # new session, original unchanged
        allowed_tools=["Read", "Edit", "Write"],
    ),
):
    if isinstance(message, ResultMessage):
        forked_id = message.session_id  # different from session_id
```

### Automatic Context Compaction

When the context window approaches its limit, the SDK automatically summarizes older history:
- Emits `SystemMessage(subtype="compact_boundary")` in the stream
- Older messages replaced with a summary
- Recent exchanges and key decisions preserved

**Important:** Instructions from early in the conversation may not survive compaction. Put persistent rules in `CLAUDE.md` (loaded via `setting_sources=["project"]`) — it's re-injected on every request.

Trigger manual compaction: pass `/compact` as a prompt string.

### Cross-Host Resumption

Sessions are machine-local. To resume on a different host (CI workers, ephemeral containers):
- Move `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl` to the same path on the new host (`cwd` must match exactly)
- Or: capture what you need (analysis output, decisions) as application state and pass to a fresh session's prompt — often more robust

---

## Chapter 8: CLAUDE.md and Project Configuration

When you set `setting_sources=["project"]`, the SDK loads filesystem configuration from the current directory and `.claude/` subdirectory. This unlocks persistent project context, shared across all agent sessions.

### CLAUDE.md — Persistent Agent Instructions

CLAUDE.md is the agent's persistent memory. Unlike the initial prompt (which may get compacted away after many turns), CLAUDE.md content is re-injected on every request via prompt caching.

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
├── CLAUDE.md              # or use CLAUDE.md at project root
├── settings.json          # declarative allow/deny rules
├── agents/                # filesystem-based agent definitions
│   └── researcher.md
├── skills/                # reusable capabilities
│   └── legal-analysis/
│       └── SKILL.md
└── commands/              # slash command shortcuts
    └── review-contract.md
```

### Skills

Skills are specialized capabilities defined in Markdown, invoked via the `Skill` tool:

```markdown
# .claude/skills/compliance-check/SKILL.md
---
description: "Check a document for compliance with GDPR, CCPA, or HIPAA"
---

Analyze the provided document text for compliance issues:
1. Check for required disclosures
2. Identify missing consent mechanisms
3. Flag data retention policies
4. Report jurisdiction-specific violations
```

---

## Chapter 9: Context Window Management

The context window is the total information available to Claude in a session. It accumulates across turns and does not reset between turns within the same session.

### What Consumes Context

| Content | When Added | Notes |
|---|---|---|
| System prompt | Every request | Fixed cost; prompt-cached (low cost after first request) |
| CLAUDE.md | Every request | Prompt-cached; put persistent rules here |
| Tool definitions | Every request | Each tool adds its schema; prompt-cached |
| Conversation history | Accumulates | Grows every turn — largest cost in long sessions |
| MCP tool schemas | Every request | Can be significant for large tool sets; use tool search |
| Skill descriptions | Session start | Short summaries; full content only when invoked |

### Strategies for Long-Running Agents

**1. Subagents for subtasks.** Each subagent starts with fresh context. The main agent's context grows by one summary (the subagent's final response), not by the subagent's full work transcript.

**2. Scope subagent tools.** Every tool definition uses context. Pass only the tools each subagent needs:

```python
"researcher": AgentDefinition(
    tools=["Read", "Grep", "Glob"],  # no Bash, no Edit — minimal surface
    ...
)
```

**3. Enable MCP tool search** for large tool sets (tools loaded on-demand instead of upfront).

**4. Lower effort for routine tasks.** `effort="low"` for agents that only need to read files — fewer reasoning tokens per turn.

**5. Be selective with MCP servers.** Each connected server adds all its tool schemas to every request. A few large MCP servers can dominate context before the agent does any work.

---

## Chapter 10: Java Integration via LangChain4J

The Claude Agent SDK is Python/TypeScript native. Java/Spring Boot projects use **LangChain4J 1.12.2+** to implement equivalent patterns, accessing Claude via the Anthropic Client SDK (not the Agent SDK directly).

### LangChain4J Dependency Setup

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

> **Note:** LangChain4J 0.25.0 has NO Anthropic provider, NO MCP client, NO tool use. You must upgrade to 1.12.2+.

### AnthropicChatModel Configuration

```java
AnthropicChatModel model = AnthropicChatModel.builder()
    .apiKey(System.getenv("ANTHROPIC_API_KEY"))
    .modelName("claude-sonnet-4-5")
    .temperature(0.0)
    .cacheSystemMessages(true)   // 90% savings on cached system prompts
    .cacheTools(true)            // cache tool definitions
    .build();
```

### Extended Thinking (for Evaluator subagent)

```java
AnthropicChatModel evaluatorModel = AnthropicChatModel.builder()
    .apiKey(apiKey)
    .modelName("claude-opus-4")
    .thinkingType("enabled")
    .thinkingBudgetTokens(10000)  // billed at full input rate; use selectively
    .build();
```

### Tool Use with AiServices

```java
interface ContractAnalyzer {
    @Tool("Flag a legal risk found in the contract")
    void flagRisk(
        @P("clause") String clause,
        @P("severity") String severity,  // HIGH | MEDIUM | LOW
        @P("description") String description
    );

    @Tool("Note an ambiguous clause")
    void noteAmbiguity(
        @P("clause") String clause,
        @P("explanation") String explanation
    );

    @Tool("Suggest an edit to a contract clause")
    void suggestEdit(
        @P("clause") String clause,
        @P("suggestion") String suggestion
    );
}
```

This replaces the "return JSON" prompt pattern entirely. Claude calls the typed methods — no `extractJson()`, no fallbacks.

### MCP Client

```java
// stdio transport (local MCP server process)
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

### Implementing Agent SDK Patterns in Java

Since the Agent SDK is Python/TS native, Java projects must implement its architectural patterns as custom abstractions:

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

**Streaming with SSE:**
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

### Prompt Caching Economics

For compliance checks where system prompt + rules = ~2000 tokens:

| Request | Tokens Charged | Cost Multiplier |
|---|---|---|
| First call | 2000 × 1.25× | 1.25× base (cache write) |
| Subsequent calls | 2000 × 0.10× | **0.10× base** (cache hit) |

Over 100 documents/day in the same jurisdiction: saves ~180K cached tokens/day.

Batch API adds 50% discount on top of caching for overnight document libraries.

---

## Chapter 11: Real-World Architecture — Legal AI Agent

This chapter applies all concepts above to redesign an existing Java Legal AI Agent from a monolithic GPT-4o service to a Claude Agent SDK-aligned multi-agent system.

### Before: Monolithic Service

```
POST /docs/{id}/analyze
    → LegalAiService.analyzeContract()  // single LLM call
        → prompt: "Analyze this contract. Return JSON: {risks:[], ambiguities:[], edits:[]}"
        → extractJson(response)          // fragile; fails on prose-wrapped JSON
        → createFallbackResult()         // silently returns riskLevel="MEDIUM"
        → cache(badResult, 30min)        // cached wrong answer
```

### After: Claude Agent SDK Architecture

```
POST /docs/{id}/analyze          ← returns {sessionId} immediately (202 Accepted)
    ↓
LegalOrchestratorAgent receives document + jurisdiction
    ↓
Auto-delegates (parallel):
    ├── contract-analyst: flagRisk(), noteAmbiguity(), suggestEdit()  ─┐
    ├── compliance-checker: autonomous MCP tool loop                    ├─ parallel
    └── legal-researcher: vectorSearch() → citations enabled           ─┘
              ↓ (waits for contract-analyst result)
    risk-scorer: scoreCategory(), addCriticalIssue()
              ↓ (if HIGH risk)
    evaluator: validateCitation(), checkConsistency()   ← Extended Thinking
              ↓
    orchestrator synthesizes final report
    ↓
SessionSaveHook persists result to Document JSONB
    ↓
GET /sessions/{sessionId}        ← poll for result
```

### Tool Definitions Replace JSON Parsing

```
Before: "Analyze this contract. Return JSON: {risks: [{clause: '...', severity: '...'}, ...]}"
    → Claude returns JSON (sometimes)
    → extractJson() parses it (sometimes works)
    → createFallbackContractAnalysis() on failure (always returns MEDIUM risk)

After: Claude calls mcp__legal-analysis__flagRisk(clause, severity, description)
    → schema-guaranteed call (strict: true)
    → Java handler stores each risk as a structured object
    → no parsing, no fallbacks, no silent failures
```

### Compliance Checker Autonomous Loop

```
compliance-checker subagent receives: document + "US-CA"
    ↓
Think: "California contract — CCPA applies."
Call: mcp__compliance__check_jurisdiction_rules(text, "US-CA")
    → 3 violations
Think: "Now check required provisions."
Call: mcp__compliance__check_required_clauses(text, "US-CA")
    → missing: privacy notice, opt-out mechanism
Think: "Scan for exposed PII."
Call: mcp__compliance__check_data_protection(text)
    → 2 emails, 1 SSN found unredacted
Think: "Have all findings. Compile report."
    → Return structured ComplianceResult (final message to orchestrator)
```

### Hook-Based Audit Trail

```java
// Replaces AOP @AfterReturning interceptors on every service method
HookRegistry registry = HookRegistry.builder()
    .on(POST_TOOL_USE, HookMatcher.all(new AuditHook(auditLogRepository)))
    .on(POST_TOOL_USE_FAILURE, HookMatcher.all(new ErrorAuditHook(auditLogRepository)))
    .on(PRE_TOOL_USE, HookMatcher.all(new PermissionHook(roleBasedAccessService)))
    .on(PRE_TOOL_USE, HookMatcher.of("^mcp__legal-analysis__", new PiiScanHook()))
    .build();
```

### Citations for Legal Research (No Hallucinations)

```python
# legal-researcher subagent tool call
async def cite_case_law(query: str, jurisdiction: str) -> dict:
    # Retrieve relevant chunks from pgvector
    chunks = vector_store.search(query, jurisdiction, top_k=10)

    # Send as Claude "document" content blocks with citations enabled
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
    # Response includes structured citation objects with char_index pointers
    return response  # cited_text doesn't count as output tokens
```

---

## Chapter 12: Key Constraints and Gotchas

A reference of all non-obvious constraints in the Claude Agent SDK:

### Architectural Constraints

| Constraint | Detail |
|---|---|
| **Subagents cannot nest** | Flat hierarchy only. Max 1 level below orchestrator. Never Agent in subagent `tools`. |
| **Subagents don't inherit parent context** | Pass everything needed via Agent tool prompt string. |
| **Citations + Tool Use incompatible** | Claude returns HTTP 400 if both enabled simultaneously. Route: tool use for analysis, citations for research. |
| **Extended Thinking + temperature/top_k** | Incompatible. Cannot set temperature with Extended Thinking enabled. |
| **Extended Thinking + forced tool use** | Incompatible. Cannot force a specific tool call with Extended Thinking. |

### Permission Constraints

| Constraint | Detail |
|---|---|
| **`bypassPermissions` propagates** | All subagents inherit it. Cannot override per-subagent. |
| **`allowedTools` doesn't restrict `bypassPermissions`** | Use `disallowed_tools` to block in bypass mode. |
| **`dontAsk` mode** | TypeScript SDK only. No Python equivalent (use `disallowed_tools` instead). |
| **Subagents don't inherit parent permissions** | Each subagent triggers its own permission prompts (use PreToolUse hooks to auto-approve for subagents). |

### SDK-Specific Constraints

| Constraint | Detail |
|---|---|
| **Custom in-process tools require streaming input (TS)** | Must use `async generator` for `prompt` — plain string won't work with in-process MCP servers. |
| **`SessionStart/SessionEnd` hooks** | TypeScript SDK only. Python uses shell command hooks via `settings.json`. |
| **MCP tool search model requirement** | Requires Sonnet 4+ or Opus 4+. Haiku models don't support tool search. |
| **Windows subagent limit** | Long subagent prompts may fail (8191 char CLI limit). Use filesystem-defined agents for complex prompts. |
| **Resume requires matching `cwd`** | Sessions stored under encoded-cwd path. Resume from different directory will not find session. |
| **Recursive hook loops** | `UserPromptSubmit` hooks that spawn subagents can recurse infinitely. Check `agent_id` before spawning. |

### Cost Constraints

| Constraint | Detail |
|---|---|
| **Extended Thinking billing** | Thinking tokens billed at full input rate. 10K budget ≈ $0.03/evaluation. Restrict to high-complexity tasks. |
| **Prompt caching write** | First request charges 1.25× base rate (cache write overhead). Paid back after 2+ cached uses. |
| **`bypassPermissions` + unbounded tools** | With no `max_turns` or `max_budget_usd`, a runaway agent in bypass mode could take an arbitrary number of actions. Always set limits in production. |

---

## Conclusion

The Claude Agent SDK represents a shift in how we build AI features: from request-response AI calls embedded in application code to autonomous agents that use tools, maintain state, spawn specialized sub-workers, and are governed by a coherent permission model.

The key architectural principles:

1. **Let Claude handle the loop.** Don't manually orchestrate prompt → parse → act → prompt chains. Give Claude tools and let it iterate.

2. **Subagents for everything big.** Context isolation + parallelization make complex workflows tractable. Keep each subagent's tool surface minimal.

3. **Hooks over AOP.** Lifecycle hooks with typed events are more predictable than aspect-oriented interception — and they let you modify agent behavior, not just observe it.

4. **MCP for all tools.** Treating every capability as an MCP tool gives you a uniform naming scheme, permission management, and tooling (server discovery, error detection, lazy loading).

5. **CLAUDE.md for persistent rules.** The initial prompt gets compacted. Persistent agent instructions belong in CLAUDE.md — re-injected on every request via prompt caching.

6. **Session IDs for continuity.** Any non-trivial user interaction should capture and store session IDs. Users should be able to resume analyses, fork explorations, and pick up where they left off.

7. **Citations for research, Tool Use for analysis.** These features are incompatible when combined. Route to the right mode based on task type.

These principles apply whether you're using the Python/TypeScript Agent SDK directly, or implementing equivalent patterns in Java via LangChain4J.

---

*Sources: Claude Agent SDK documentation at https://platform.claude.com/docs/en/api/agent-sdk/overview*  
*LangChain4J documentation at https://docs.langchain4j.dev/*  
*MCP specification at https://modelcontextprotocol.io/*
```