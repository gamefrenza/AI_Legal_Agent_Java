# 🔐 Dynamic Compliance Rules System

## Overview

The Legal AI Agent now supports **dynamic loading** of compliance rules from JSON configuration files. This allows easy addition of new jurisdictions and rules without code changes.

## ✅ Supported Jurisdictions

### Current Support (26+ Rules):
- **🇺🇸 United States (US)** - Federal regulations
  - HIPAA (Healthcare)
  - GLBA (Financial)
  - FERPA (Education)
  - COPPA (Children's privacy)

- **🌴 California (US-CA)** - CCPA
  - Email data collection
  - Personal information disclosure
  - Sale/sharing requirements

- **🗽 New York (US-NY)** - NY SHIELD Act
  - Data breach notification
  - SSN protection

- **🇪🇺 European Union (EU)** - GDPR
  - Email protection
  - Data transfers
  - Consent requirements
  - Right to erasure

- **🇬🇧 United Kingdom (UK)** - UK GDPR
  - Post-Brexit data protection
  - International transfers

- **🇨🇦 Canada (CANADA)** - PIPEDA
  - Personal information protection

- **🇦🇺 Australia (AUSTRALIA)** - Privacy Act
  - Australian Privacy Principles

- **🇧🇷 Brazil (BRAZIL)** - LGPD
  - General Data Protection Law

- **🌍 Global (GLOBAL)** - Universal rules
  - PII protection
  - PCI DSS

---

## 📋 JSON Rule Format

### Rule Structure

```json
{
  "jurisdiction": "US-CA",
  "ruleName": "CCPA_EMAIL",
  "description": "California Consumer Privacy Act - Email data collection",
  "regexPattern": "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b",
  "severity": "HIGH",
  "active": true
}
```

### Field Descriptions

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `jurisdiction` | String | Yes | Jurisdiction code (US, EU, UK, US-CA, etc.) |
| `ruleName` | String | Yes | Unique rule identifier |
| `description` | String | Yes | Human-readable description |
| `regexPattern` | String | Yes | Regular expression for detection |
| `severity` | String | No | HIGH, MEDIUM, LOW (default: MEDIUM) |
| `active` | Boolean | No | Whether rule is enabled (default: true) |

---

## 🚀 Usage

### Automatic Loading on Startup

Rules are automatically loaded from `compliance-rules.json` when the application starts:

```yaml
# application.yml
compliance:
  rules:
    file: classpath:compliance-rules.json
    autoload: true  # Auto-load on startup
```

### Manual Reload

Reload rules without restarting:

```bash
POST /admin/compliance/rules/reload
Authorization: Basic admin:admin123

Response:
{
  "success": true,
  "message": "Compliance rules reloaded successfully",
  "rulesLoaded": 26
}
```

### Import Custom Rules

Import rules from JSON string:

```bash
POST /admin/compliance/rules/import
Authorization: Basic admin:admin123
Content-Type: application/json

{
  "jsonContent": "{\"rules\": [...]}"
}
```

### Get Rule Statistics

View rule distribution:

```bash
GET /admin/compliance/rules/statistics
Authorization: Basic admin:admin123

Response:
{
  "totalRules": 26,
  "activeRules": 26,
  "inactiveRules": 0,
  "byJurisdiction": {
    "US": 5,
    "US-CA": 3,
    "US-NY": 2,
    "EU": 4,
    "UK": 3,
    "CANADA": 2,
    "AUSTRALIA": 1,
    "BRAZIL": 1,
    "GLOBAL": 5
  },
  "bySeverity": {
    "HIGH": 20,
    "MEDIUM": 5,
    "LOW": 1
  }
}
```

### Get Rules by Jurisdiction

```bash
GET /admin/compliance/rules/jurisdiction/US-CA
Authorization: Basic admin:admin123

Response: [
  {
    "id": 1,
    "jurisdiction": "US-CA",
    "ruleName": "CCPA_EMAIL",
    "description": "...",
    "regexPattern": "...",
    "severity": "HIGH",
    "active": true
  },
  ...
]
```

### Toggle Rule Status

Enable/disable specific rule:

```bash
PATCH /admin/compliance/rules/5/toggle
Authorization: Basic admin:admin123

Response: {
  "id": 5,
  "ruleName": "GDPR_EMAIL",
  "active": false  # Toggled
}
```

---

## 📝 Adding New Jurisdictions

### Step 1: Add Rules to JSON

Edit `compliance-rules.json`:

```json
{
  "rules": [
    // ... existing rules ...
    {
      "jurisdiction": "JAPAN",
      "ruleName": "APPI_PERSONAL_INFO",
      "description": "Act on the Protection of Personal Information (Japan)",
      "regexPattern": "(?i)(個人情報|personal.?information)",
      "severity": "HIGH",
      "active": true
    },
    {
      "jurisdiction": "SINGAPORE",
      "ruleName": "PDPA_CONSENT",
      "description": "Personal Data Protection Act (Singapore)",
      "regexPattern": "(?i)(consent|personal.?data)",
      "severity": "HIGH",
      "active": true
    }
  ]
}
```

### Step 2: Reload Rules

```bash
POST /admin/compliance/rules/reload
```

### Step 3: Verify

```bash
GET /admin/compliance/rules/statistics
```

---

## 🔍 Example Rules

### Email Detection (GDPR)

```json
{
  "jurisdiction": "EU",
  "ruleName": "GDPR_EMAIL",
  "description": "EU GDPR - Email addresses are personal data",
  "regexPattern": "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b",
  "severity": "HIGH",
  "active": true
}
```

### Data Transfer (GDPR)

```json
{
  "jurisdiction": "EU",
  "ruleName": "GDPR_DATA_TRANSFER",
  "description": "International data transfers require safeguards",
  "regexPattern": "(?i)(transfer|transmit).{0,50}(outside|international|third.?country)",
  "severity": "HIGH",
  "active": true
}
```

### Medical Records (HIPAA)

```json
{
  "jurisdiction": "US",
  "ruleName": "HIPAA_MEDICAL_RECORD",
  "description": "Medical record numbers are PHI",
  "regexPattern": "\\b(MRN|medical.?record.?number)[:\\s]*\\d{6,10}\\b",
  "severity": "HIGH",
  "active": true
}
```

---

## 🎯 Integration with Document Analysis

### Automatic Compliance Checking

When documents are uploaded, they are automatically checked against rules:

```bash
POST /docs/upload
- File uploaded
- Tika extracts text
- ComplianceEngineService loads rules for jurisdiction
- Regex patterns applied
- Violations detected
- AI validation (GPT-4o)
- Results returned
```

### Example Workflow

```java
// 1. Upload document
POST /docs/upload
file=contract.pdf
jurisdiction=US-CA

// 2. System checks CCPA rules:
// - CCPA_EMAIL
// - CCPA_PERSONAL_INFO
// - CCPA_SALE_DISCLOSURE

// 3. Returns compliance result
{
  "compliant": false,
  "violations": [
    {
      "ruleName": "CCPA_EMAIL",
      "severity": "HIGH",
      "description": "Email found without consent notice",
      "matchedText": "john@example.com"
    }
  ]
}
```

---

## ⚙️ Configuration Options

### application.yml

```yaml
compliance:
  rules:
    file: classpath:compliance-rules.json  # Path to rules file
    autoload: true                          # Auto-load on startup
```

### Custom File Location

```yaml
compliance:
  rules:
    file: file:/path/to/custom-rules.json
    autoload: true
```

### Disable Auto-load

```yaml
compliance:
  rules:
    autoload: false  # Manually load via API
```

---

## 🛠️ Best Practices

### 1. Rule Naming Convention
```
{JURISDICTION}_{REGULATION}_{SPECIFIC}
Examples:
- US_CA_CCPA_EMAIL
- EU_GDPR_CONSENT
- UK_GDPR_TRANSFER
```

### 2. Severity Levels
- **HIGH** - Legal requirement, critical violation
- **MEDIUM** - Important but not critical
- **LOW** - Best practice, recommendation

### 3. Regex Patterns
- Use `(?i)` for case-insensitive matching
- Use `.{0,50}` for flexible spacing
- Test patterns thoroughly before deployment
- Escape special characters properly

### 4. Version Control
```json
{
  "metadata": {
    "version": "1.0",
    "lastUpdated": "2024-01-15",
    "description": "Comprehensive compliance rules"
  }
}
```

---

## 📊 Monitoring & Reporting

### View Loaded Rules

```bash
GET /admin/compliance/rules
```

### Check Rule by ID

```bash
GET /admin/compliance/rules/{id}
```

### Filter Active Rules

```bash
GET /admin/compliance/rules?active=true
```

### Audit Trail

All rule changes are logged:
```
AUDIT - COMPLIANCE_RULES_LOADED: Count=26, File=compliance-rules.json
AUDIT - ADMIN_RULES_RELOADED: Count=26
AUDIT - ADMIN_RULE_TOGGLED: RuleId=5, RuleName=GDPR_EMAIL, Active=false
```

---

## 🚨 Troubleshooting

### Rules Not Loading

**Problem:** Rules don't load on startup

**Solutions:**
1. Check file path: `classpath:compliance-rules.json`
2. Verify JSON syntax
3. Check application logs for errors
4. Ensure `autoload: true` in application.yml

### Invalid Regex Pattern

**Problem:** Regex pattern causes errors

**Solutions:**
1. Test regex with online tools (regex101.com)
2. Escape special characters: `\\.` for dots
3. Use `(?i)` for case-insensitive
4. Check logs for regex compilation errors

### Duplicate Rules

**Problem:** Same rule loaded multiple times

**Solutions:**
- System automatically updates existing rules
- Check `ruleName` and `jurisdiction` combination
- Use unique identifiers

---

## 📚 API Reference

### Admin Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/admin/compliance/rules/reload` | Reload from JSON |
| GET | `/admin/compliance/rules/statistics` | Get statistics |
| POST | `/admin/compliance/rules/import` | Import JSON string |
| GET | `/admin/compliance/rules/jurisdiction/{code}` | Get by jurisdiction |
| PATCH | `/admin/compliance/rules/{id}/toggle` | Toggle active status |
| GET | `/admin/compliance/rules` | List all rules |
| POST | `/admin/compliance/rules` | Create new rule |
| PUT | `/admin/compliance/rules/{id}` | Update rule |
| DELETE | `/admin/compliance/rules/{id}` | Delete rule |

---

## 🎉 Summary

The dynamic compliance rules system provides:

✅ **Easy jurisdiction management** - Add new rules without code changes
✅ **Flexible configuration** - JSON-based rule definitions
✅ **Hot reload** - Update rules without restart
✅ **Comprehensive coverage** - 9+ jurisdictions, 26+ rules
✅ **AI + Rule-based** - Hybrid validation approach
✅ **Full audit trail** - All changes logged
✅ **Admin controls** - Toggle, import, reload via API

**Now supporting:** US (Federal & States), EU, UK, Canada, Australia, Brazil, and Global standards!

