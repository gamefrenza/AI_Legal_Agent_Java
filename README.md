# ðŸ›ï¸ Legal AI Agent

An intelligent document management system powered by OpenAI GPT-4o and LangChain4J for legal document analysis, compliance checking, and risk assessment.

## ðŸŒŸ Features

### Core Capabilities
- **ðŸ“¤ Document Upload & Management** - Parse, encrypt, and store legal documents
- **ðŸ¤– AI-Powered Analysis** - Contract analysis using GPT-4o via LangChain4J
- **ðŸ” Legal Research** - AI-assisted legal research with citations
- **âš–ï¸ Compliance Engine** - Jurisdiction-specific rule validation
- **ðŸ”’ PII Detection** - Automatic detection and redaction of sensitive data
- **ðŸ“Š Risk Assessment** - Comprehensive risk scoring (0-10 scale)
- **ðŸ” Enterprise Security** - RBAC, encryption, audit logging
- **ðŸ“œ Version Control** - Document versioning with JGit
- **ðŸ“ Audit Trail** - Immutable, blockchain-ready audit logs

### Technologies
- **Backend:** Spring Boot 3.2.0, Java 17
- **AI/ML:** LangChain4J, OpenAI GPT-4o
- **Security:** Spring Security, BouncyCastle AES-256
- **Database:** PostgreSQL, Redis
- **Document Processing:** Apache Tika
- **Testing:** JUnit 5, Mockito, H2
- **Frontend:** Thymeleaf, Vanilla JS SPA

---

## ðŸš€ Quick Start

### Prerequisites
```bash
- Java 17+
- Maven 3.8+
- PostgreSQL 14+
- Redis 6+
- OpenAI API Key
```

### 1. Clone Repository
```bash
git clone <repository-url>
cd AI_Legal_Agent_Java/agent
```

### 2. Set Environment Variables
```bash
export DB_URL=jdbc:postgresql://localhost:5432/legaldb
export DB_USER=postgres
export DB_PASS=your_password
export OPENAI_API_KEY=sk-your-openai-key
export REDIS_HOST=localhost
export REDIS_PORT=6379
export ENCRYPTION_KEY=LegalAI-AES256-SecureKey-32chars   # exactly 32 UTF-8 bytes
```

### 3. Start Dependencies

#### PostgreSQL
```bash
docker run -d \
  --name postgres \
  -p 5432:5432 \
  -e POSTGRES_PASSWORD=your_password \
  -e POSTGRES_DB=legaldb \
  postgres:14
```

#### Redis
```bash
docker run -d \
  --name redis \
  -p 6379:6379 \
  redis:latest
```

### 4. Build & Run
```bash
mvn clean install
mvn spring-boot:run
```

### 5. Access Application
- **Thymeleaf UI:** http://localhost:8080/
- **SPA Dashboard:** http://localhost:8080/static/index.html
- **API Docs:** http://localhost:8080/docs/health

---

## 🐳 Running with Podman (Containers — Recommended)

The easiest way to run the full stack (app + PostgreSQL + Redis) locally is with Podman. No need to install Java, Maven, PostgreSQL, or Redis on your machine.

### Prerequisites

| Tool | Install |
|---|---|
| **Podman Desktop** | https://podman.io/getting-started/installation |
| **podman-compose** | `pip install podman-compose` *(or use Podman 4.7+ built-in `podman compose`)* |

Start the Podman machine (Windows/macOS):
```powershell
podman machine start
```

### 1. Configure Secrets

Copy the example env file and fill in your values:
```powershell
Copy-Item .env.example .env
notepad .env
```

Edit `.env`:
```env
# Required
OPENAI_API_KEY=sk-...your-openai-key...
DB_USER=legalai
DB_PASS=changeme_use_strong_password

# Optional — leave blank for no Redis password
REDIS_PASSWORD=

# Optional — must be exactly 32 UTF-8 characters
ENCRYPTION_KEY=LegalAI-AES256-SecureKey-32chars
```

> ⚠️ **Never commit `.env` to source control.** It is already excluded via `.dockerignore`.

### 2. Build and Start All Services

```powershell
podman compose up --build
```

This starts three containers:

| Container | Image | Exposed Port |
|---|---|---|
| `postgres` | `postgres:16-alpine` | internal only |
| `redis` | `redis:7-alpine` | internal only |
| `app` | built from `Dockerfile` | `8080 → 8080` |

The app waits for PostgreSQL and Redis health checks to pass before starting. The first build downloads Maven dependencies and may take a few minutes.

### 3. Access the Application

| Interface | URL |
|---|---|
| Thymeleaf UI | http://localhost:8080/ |
| SPA Dashboard | http://localhost:8080/static/index.html |

Log in with any of the default users listed in the next section.

### Useful Commands

```powershell
# View live logs for the app
podman compose logs -f app

# Stop all containers (keeps data volumes)
podman compose down

# Stop and wipe all data (DB + Redis volumes — destructive)
podman compose down -v

# Rebuild only the app after a code change
podman compose up --build app

# Open a shell inside the running app container
podman exec -it ai_legal_agent_java-main-app-1 sh
```

### Podman Troubleshooting

**App fails to connect to PostgreSQL or Redis**
```powershell
podman compose logs postgres
podman compose logs redis
# Check health status
podman inspect ai_legal_agent_java-main-postgres-1 --format "{{.State.Health.Status}}"
```

**Port 8080 already in use**
```powershell
# Find conflicting process (Windows)
netstat -ano | findstr :8080
# Or change the host port in compose.yaml: "8081:8080"
```

**Podman machine not running (Windows/macOS)**
```powershell
podman machine start
podman machine status
```

---

## ðŸ‘¥ Default Users

| Username | Password | Roles | Capabilities |
|----------|----------|-------|--------------|
| `lawyer` | `lawyer123` | LAWYER | Upload, analyze, research |
| `admin` | `admin123` | ADMIN | Full system access |
| `clerk` | `clerk123` | CLERK | View, list, research |

---

## ðŸ“š API Documentation

### Document Endpoints

#### Upload Document
```bash
POST /docs/upload
Authorization: Basic <base64(username:password)>
Content-Type: multipart/form-data

Parameters:
- file: MultipartFile (required)
- jurisdiction: String (required) - US, US-CA, US-NY, EU, UK
- analyze: Boolean (optional, default: true)
```

#### Legal Research
```bash
GET /docs/search?query=non-compete&jurisdiction=US-CA
Authorization: Basic <credentials>

Response:
{
  "query": "non-compete enforceability",
  "jurisdiction": "US-CA",
  "summary": "...",
  "statutes": [...],
  "cases": [...],
  "recommendations": [...]
}
```

#### Risk Assessment
```bash
GET /docs/{id}/risk
Authorization: Basic <credentials>

Response:
{
  "overallRiskScore": 7,
  "riskCategories": [
    {"category": "Liability", "score": 8, "details": "..."}
  ],
  "criticalIssues": [...],
  "recommendations": [...]
}
```

### Admin Endpoints

#### Create User
```bash
POST /admin/users
Authorization: Basic admin:admin123
Content-Type: application/json

{
  "username": "newlawyer",
  "password": "SecurePass123!",
  "roles": ["LAWYER"]
}
```

#### Get System Statistics
```bash
GET /admin/statistics
Authorization: Basic admin:admin123

Response:
{
  "auditLogs": {
    "totalRecent": 100,
    "successCount": 87,
    "failureCount": 13,
    "byAction": {...},
    "byUser": {...}
  },
  "sessions": {...}
}
```

---

## ðŸ§ª Running Tests

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=DocumentServiceTest

# Run with coverage
mvn clean test jacoco:report
```

### Test Configuration
Tests use H2 in-memory database and mock dependencies:
- Mocked Tika for document parsing
- Mocked ComplianceEngineService
- Mocked repositories
- Spring Security test context

---

## ðŸ” Security Features

### Authentication & Authorization
- BCrypt password hashing
- Role-based access control (RBAC)
- Method-level security with `@PreAuthorize`
- Session concurrency control (max 1 per user)
- Redis-backed distributed sessions

### Data Protection
- AES-256 encryption with BouncyCastle
- PII detection (emails, SSNs, phone numbers, credit cards)
- Automatic data redaction
- Secure key management (TODO: vault integration)

### Audit & Compliance
- Immutable audit logs (append-only)
- AOP-based automatic logging
- Compliance rule engine
- Jurisdiction-specific validation
- SHA-256 hash chaining on every audit log entry (Merkle chain, append-only)

---

## ðŸ“Š Architecture

```
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                        Client Layer                              â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”           â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”          â”‚
â”‚  â”‚  Thymeleaf UI    â”‚           â”‚   SPA Dashboard   â”‚          â”‚
â”‚  â”‚  (Server-side)   â”‚           â”‚  (Client-side)    â”‚          â”‚
â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜           â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜          â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                              â”‚
                              â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                      Controller Layer                            â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”   â”‚
â”‚  â”‚ HomeController   â”‚  â”‚ DocumentControllerâ”‚  â”‚AdminControllerâ”‚ â”‚
â”‚  â”‚  (Thymeleaf)     â”‚  â”‚   (REST API)      â”‚  â”‚  (Admin)    â”‚  â”‚
â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜   â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                              â”‚
                              â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                       Service Layer                              â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”     â”‚
â”‚  â”‚DocumentServiceâ”‚  â”‚LegalAiServiceâ”‚  â”‚ComplianceEngineâ”‚      â”‚
â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜     â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”     â”‚
â”‚  â”‚ RBAC Service â”‚  â”‚ActivityMonitorâ”‚  â”‚ Session Service  â”‚     â”‚
â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜     â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                              â”‚
                              â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                    Repository Layer                              â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”   â”‚
â”‚  â”‚DocumentRepositoryâ”‚  â”‚ComplianceRuleRepoâ”‚  â”‚AuditLogRepoâ”‚   â”‚
â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜   â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
                              â”‚
                              â–¼
â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚                     Data Layer                                   â”‚
â”‚  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”  â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”   â”‚
â”‚  â”‚ PostgreSQL   â”‚  â”‚  Redis   â”‚  â”‚ OpenAI  â”‚  â”‚  Tika    â”‚   â”‚
â”‚  â”‚  (Docs)      â”‚  â”‚(Sessions)â”‚  â”‚(GPT-4o) â”‚  â”‚(Parsing) â”‚   â”‚
â”‚  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜  â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜   â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”˜
```

---

## ðŸ”§ Configuration

### application.yml
```yaml
server:
  port: 8080

spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USER}
    password: ${DB_PASS}
  
  jpa:
    hibernate:
      ddl-auto: update  # Use 'validate' in production
  
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  
  session:
    store-type: redis
    redis:
      flush-mode: on_save

openai:
  api-key: ${OPENAI_API_KEY}

logging:
  level:
    com.legalai: DEBUG
```

---

## ðŸ“ Project Structure

```
agent/
â”œâ”€â”€ src/main/java/com/legalai/agent/
â”‚   â”œâ”€â”€ LegalAiAgentApplication.java
â”‚   â”œâ”€â”€ config/
â”‚   â”‚   â”œâ”€â”€ SecurityConfig.java
â”‚   |   â””â”€â”€ EncryptionConfig.java
â”‚   â”œâ”€â”€ controller/
â”‚   â”‚   â”œâ”€â”€ DocumentController.java
â”‚   â”‚   â”œâ”€â”€ AdminController.java
â”‚   â”‚   â””â”€â”€ HomeController.java
â”‚   â”œâ”€â”€ entity/
â”‚   â”‚   â”œâ”€â”€ Document.java
â”‚   â”‚   â”œâ”€â”€ ComplianceRule.java
â”‚   â”‚   â””â”€â”€ AuditLog.java
â”‚   â”œâ”€â”€ repository/
â”‚   â”‚   â”œâ”€â”€ DocumentRepository.java
â”‚   â”‚   â”œâ”€â”€ ComplianceRuleRepository.java
â”‚   â”‚   â””â”€â”€ AuditLogRepository.java
â”‚   â”œâ”€â”€ service/
â”‚   â”‚   â”œâ”€â”€ DocumentService.java
â”‚   â”‚   â”œâ”€â”€ LegalAiService.java
â”‚   â”‚   â”œâ”€â”€ ComplianceEngineService.java
â”‚   â”‚   â”œâ”€â”€ RoleBasedAccessService.java
â”‚   â”‚   â”œâ”€â”€ ActivityMonitorService.java
â”‚   â”‚   â”œâ”€â”€ SessionService.java
â”‚   â”‚   â””â”€â”€ DocumentVersionService.java
â”‚   â””â”€â”€ security/
â”‚       â””â”€â”€ JwtAuthenticationFilter.java
â”œâ”€â”€ src/main/resources/
â”‚   â”œâ”€â”€ application.yml
â”‚   â”œâ”€â”€ static/
â”‚   â”‚   â”œâ”€â”€ index.html
â”‚   â”‚   â”œâ”€â”€ css/styles.css
â”‚   â”‚   â””â”€â”€ js/app.js
â”‚   â””â”€â”€ templates/
â”‚       â””â”€â”€ index.html
â””â”€â”€ src/test/
    â”œâ”€â”€ java/com/legalai/agent/service/
    â”‚   â””â”€â”€ DocumentServiceTest.java
    â””â”€â”€ resources/
        â””â”€â”€ application-test.yml
```

---

## ðŸŽ¯ Use Cases

### 1. Contract Analysis
```bash
# Upload contract for AI analysis
curl -X POST http://localhost:8080/docs/upload \
  -u lawyer:lawyer123 \
  -F "file=@contract.pdf" \
  -F "jurisdiction=US-CA" \
  -F "analyze=true"
```

### 2. Legal Research
```bash
# Research non-compete clauses
curl -X GET "http://localhost:8080/docs/search?query=non-compete%20enforceability&jurisdiction=US-CA" \
  -u lawyer:lawyer123
```

### 3. Compliance Check
```bash
# Check document compliance
curl -X POST http://localhost:8080/docs/123/compliance \
  -u lawyer:lawyer123
```

### 4. PII Redaction
```bash
# Redact sensitive data
curl -X POST http://localhost:8080/docs/redact \
  -u lawyer:lawyer123 \
  -H "Content-Type: application/json" \
  -d '{"text": "Contact John at john@example.com or 555-1234"}'
```

---

## ðŸ› ï¸ Troubleshooting

### Common Issues

**Issue:** Connection to PostgreSQL failed
```bash
# Check if PostgreSQL is running
docker ps | grep postgres

# Check connection
psql -h localhost -U postgres -d legaldb
```

**Issue:** Redis connection refused
```bash
# Check if Redis is running
docker ps | grep redis

# Test connection
redis-cli ping
```

**Issue:** OpenAI API errors
```bash
# Verify API key
echo $OPENAI_API_KEY

# Test API key
curl https://api.openai.com/v1/models \
  -H "Authorization: Bearer $OPENAI_API_KEY"
```

---

## ðŸ“„ License

This project is licensed under the MIT License - see the LICENSE file for details.

---

## ðŸ¤ Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## ðŸ“§ Contact

For questions or support, please contact the development team.

---

## ðŸ™ Acknowledgments

- OpenAI for GPT-4o
- LangChain4J for AI integration
- Spring Boot community
- Apache Tika for document parsing
- BouncyCastle for encryption

---

**Built with â¤ï¸ for the legal industry**

