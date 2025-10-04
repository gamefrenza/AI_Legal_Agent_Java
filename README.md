# 🏛️ Legal AI Agent

An intelligent document management system powered by OpenAI GPT-4o and LangChain4J for legal document analysis, compliance checking, and risk assessment.

## 🌟 Features

### Core Capabilities
- **📤 Document Upload & Management** - Parse, encrypt, and store legal documents
- **🤖 AI-Powered Analysis** - Contract analysis using GPT-4o via LangChain4J
- **🔍 Legal Research** - AI-assisted legal research with citations
- **⚖️ Compliance Engine** - Jurisdiction-specific rule validation
- **🔒 PII Detection** - Automatic detection and redaction of sensitive data
- **📊 Risk Assessment** - Comprehensive risk scoring (0-10 scale)
- **🔐 Enterprise Security** - RBAC, encryption, audit logging
- **📜 Version Control** - Document versioning with JGit
- **📝 Audit Trail** - Immutable, blockchain-ready audit logs

### Technologies
- **Backend:** Spring Boot 3.2.0, Java 17
- **AI/ML:** LangChain4J, OpenAI GPT-4o
- **Security:** Spring Security, BouncyCastle AES-256
- **Database:** PostgreSQL, Redis
- **Document Processing:** Apache Tika
- **Testing:** JUnit 5, Mockito, H2
- **Frontend:** Thymeleaf, Vanilla JS SPA

---

## 🚀 Quick Start

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

## 👥 Default Users

| Username | Password | Roles | Capabilities |
|----------|----------|-------|--------------|
| `lawyer` | `lawyer123` | LAWYER | Upload, analyze, research |
| `admin` | `admin123` | ADMIN | Full system access |
| `clerk` | `clerk123` | CLERK | View, list, research |

---

## 📚 API Documentation

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

## 🧪 Running Tests

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

## 🔐 Security Features

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
- Blockchain-ready log hashing (commented)

---

## 📊 Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client Layer                              │
│  ┌──────────────────┐           ┌───────────────────┐          │
│  │  Thymeleaf UI    │           │   SPA Dashboard   │          │
│  │  (Server-side)   │           │  (Client-side)    │          │
│  └──────────────────┘           └───────────────────┘          │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Controller Layer                            │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────┐   │
│  │ HomeController   │  │ DocumentController│  │AdminController│ │
│  │  (Thymeleaf)     │  │   (REST API)      │  │  (Admin)    │  │
│  └──────────────────┘  └──────────────────┘  └────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                       Service Layer                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐     │
│  │DocumentService│  │LegalAiService│  │ComplianceEngine│      │
│  └──────────────┘  └──────────────┘  └──────────────────┘     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐     │
│  │ RBAC Service │  │ActivityMonitor│  │ Session Service  │     │
│  └──────────────┘  └──────────────┘  └──────────────────┘     │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Repository Layer                              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌────────────┐   │
│  │DocumentRepository│  │ComplianceRuleRepo│  │AuditLogRepo│   │
│  └──────────────────┘  └──────────────────┘  └────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Data Layer                                   │
│  ┌──────────────┐  ┌──────────┐  ┌─────────┐  ┌──────────┐   │
│  │ PostgreSQL   │  │  Redis   │  │ OpenAI  │  │  Tika    │   │
│  │  (Docs)      │  │(Sessions)│  │(GPT-4o) │  │(Parsing) │   │
│  └──────────────┘  └──────────┘  └─────────┘  └──────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🔧 Configuration

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

## 📝 Project Structure

```
agent/
├── src/main/java/com/legalai/agent/
│   ├── LegalAiAgentApplication.java
│   ├── config/
│   │   └── SecurityConfig.java
│   ├── controller/
│   │   ├── DocumentController.java
│   │   ├── AdminController.java
│   │   └── HomeController.java
│   ├── entity/
│   │   ├── Document.java
│   │   ├── ComplianceRule.java
│   │   └── AuditLog.java
│   ├── repository/
│   │   ├── DocumentRepository.java
│   │   ├── ComplianceRuleRepository.java
│   │   └── AuditLogRepository.java
│   ├── service/
│   │   ├── DocumentService.java
│   │   ├── LegalAiService.java
│   │   ├── ComplianceEngineService.java
│   │   ├── RoleBasedAccessService.java
│   │   ├── ActivityMonitorService.java
│   │   ├── SessionService.java
│   │   └── DocumentVersionService.java
│   └── security/
│       └── JwtAuthenticationFilter.java
├── src/main/resources/
│   ├── application.yml
│   ├── static/
│   │   ├── index.html
│   │   ├── css/styles.css
│   │   └── js/app.js
│   └── templates/
│       └── index.html
└── src/test/
    ├── java/com/legalai/agent/service/
    │   └── DocumentServiceTest.java
    └── resources/
        └── application-test.yml
```

---

## 🎯 Use Cases

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

## 🛠️ Troubleshooting

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

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

---

## 📧 Contact

For questions or support, please contact the development team.

---

## 🙏 Acknowledgments

- OpenAI for GPT-4o
- LangChain4J for AI integration
- Spring Boot community
- Apache Tika for document parsing
- BouncyCastle for encryption

---

**Built with ❤️ for the legal industry**

