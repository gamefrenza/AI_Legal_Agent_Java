// API Configuration
const API_BASE = window.location.origin;
const API_DOCS = `${API_BASE}/docs`;

// Authentication credentials (for demo - in production use proper auth)
let authHeader = 'Basic ' + btoa('lawyer:lawyer123');

// Tab Management
function showTab(tabName) {
    // Hide all tabs
    document.querySelectorAll('.tab-content').forEach(tab => {
        tab.classList.remove('active');
    });
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.classList.remove('active');
    });
    
    // Show selected tab
    document.getElementById(`${tabName}-tab`).classList.add('active');
    event.target.classList.add('active');
}

// Upload Form Handler
document.getElementById('upload-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const fileInput = document.getElementById('file-input');
    const jurisdiction = document.getElementById('jurisdiction').value;
    const runAnalysis = document.getElementById('run-analysis').checked;
    
    if (!fileInput.files[0]) {
        showAlert('Please select a file', 'error');
        return;
    }
    
    const formData = new FormData();
    formData.append('file', fileInput.files[0]);
    formData.append('jurisdiction', jurisdiction);
    formData.append('analyze', runAnalysis);
    
    showLoading('upload', true);
    
    try {
        const response = await fetch(`${API_DOCS}/upload`, {
            method: 'POST',
            headers: {
                'Authorization': authHeader
            },
            body: formData
        });
        
        if (!response.ok) {
            throw new Error(`Upload failed: ${response.statusText}`);
        }
        
        const result = await response.json();
        displayUploadResult(result);
        showAlert('Document uploaded and analyzed successfully!', 'success');
        
    } catch (error) {
        console.error('Upload error:', error);
        showAlert(`Error: ${error.message}`, 'error');
    } finally {
        showLoading('upload', false);
    }
});

// Search Form Handler
document.getElementById('search-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const query = document.getElementById('search-query').value;
    const jurisdiction = document.getElementById('search-jurisdiction').value;
    
    showLoading('search', true);
    
    try {
        const response = await fetch(`${API_DOCS}/search?query=${encodeURIComponent(query)}&jurisdiction=${jurisdiction}`, {
            headers: {
                'Authorization': authHeader
            }
        });
        
        if (!response.ok) {
            throw new Error(`Research failed: ${response.statusText}`);
        }
        
        const result = await response.json();
        displaySearchResult(result);
        
    } catch (error) {
        console.error('Search error:', error);
        showAlert(`Error: ${error.message}`, 'error');
    } finally {
        showLoading('search', false);
    }
});

// Display Upload Result
function displayUploadResult(result) {
    const container = document.getElementById('upload-result');
    container.style.display = 'block';
    
    let html = '<div class="result-header"><h3>📊 Analysis Results</h3></div>';
    
    if (result.document) {
        html += `
            <div class="alert alert-success">
                <strong>Document Uploaded:</strong> ${result.document.fileName}<br>
                <strong>ID:</strong> ${result.document.id}<br>
                <strong>Jurisdiction:</strong> ${result.document.jurisdiction}
            </div>
        `;
    }
    
    if (result.analysis) {
        const analysis = result.analysis;
        
        // Overall Summary
        html += `<div style="margin: 20px 0;">
            <h4>📋 Summary</h4>
            <p>${analysis.summary || 'Analysis completed successfully'}</p>
            <div style="margin: 10px 0;">
                <span class="badge badge-${getRiskBadgeClass(analysis.overallRiskLevel)}">
                    Risk Level: ${analysis.overallRiskLevel || 'MEDIUM'}
                </span>
            </div>
        </div>`;
        
        // Risks
        if (analysis.risks && analysis.risks.length > 0) {
            html += '<div style="margin: 20px 0;"><h4>⚠️ Identified Risks</h4>';
            analysis.risks.forEach(risk => {
                html += `
                    <div class="risk-item ${risk.severity.toLowerCase()}">
                        <strong>${risk.severity}</strong>: ${risk.description}
                    </div>
                `;
            });
            html += '</div>';
        }
        
        // Ambiguities
        if (analysis.ambiguities && analysis.ambiguities.length > 0) {
            html += '<div style="margin: 20px 0;"><h4>❓ Ambiguities</h4><ul>';
            analysis.ambiguities.forEach(amb => {
                html += `<li>${amb}</li>`;
            });
            html += '</ul></div>';
        }
        
        // Suggestions
        if (analysis.suggestions && analysis.suggestions.length > 0) {
            html += '<div style="margin: 20px 0;"><h4>💡 Suggestions</h4>';
            analysis.suggestions.forEach(sug => {
                html += `<div class="suggestion-item">${sug}</div>`;
            });
            html += '</div>';
        }
    }
    
    if (result.compliance) {
        const compliance = result.compliance;
        html += `
            <div style="margin: 20px 0;">
                <h4>✓ Compliance Check</h4>
                <span class="badge badge-${compliance.overallCompliant ? 'success' : 'danger'}">
                    ${compliance.overallCompliant ? 'COMPLIANT' : 'NON-COMPLIANT'}
                </span>
                <p style="margin-top: 10px;">${compliance.summary || ''}</p>
            </div>
        `;
        
        if (compliance.fails && compliance.fails.length > 0) {
            html += '<div style="margin: 10px 0;"><strong>Violations:</strong><ul>';
            compliance.fails.forEach(fail => {
                html += `<li><strong>${fail.requirement}</strong> (${fail.severity}): ${fail.details}</li>`;
            });
            html += '</ul></div>';
        }
    }
    
    container.innerHTML = html;
}

// Display Search Result
function displaySearchResult(result) {
    const container = document.getElementById('search-result');
    container.style.display = 'block';
    
    let html = '<div class="result-header"><h3>🔍 Research Results</h3></div>';
    
    html += `<div style="margin: 20px 0;">
        <h4>Summary</h4>
        <p>${result.summary}</p>
    </div>`;
    
    if (result.statutes && result.statutes.length > 0) {
        html += '<div style="margin: 20px 0;"><h4>📜 Relevant Statutes</h4><ul>';
        result.statutes.forEach(statute => {
            html += `<li>${statute}</li>`;
        });
        html += '</ul></div>';
    }
    
    if (result.cases && result.cases.length > 0) {
        html += '<div style="margin: 20px 0;"><h4>⚖️ Case Law</h4>';
        result.cases.forEach(caseItem => {
            html += `
                <div class="risk-item">
                    <strong>${caseItem.name}</strong><br>
                    <em>${caseItem.citation}</em><br>
                    ${caseItem.relevance}
                </div>
            `;
        });
        html += '</div>';
    }
    
    if (result.recommendations && result.recommendations.length > 0) {
        html += '<div style="margin: 20px 0;"><h4>💡 Recommendations</h4><ul>';
        result.recommendations.forEach(rec => {
            html += `<li>${rec}</li>`;
        });
        html += '</ul></div>';
    }
    
    container.innerHTML = html;
}

// Load Documents
async function loadDocuments() {
    try {
        const response = await fetch(`${API_DOCS}/list`, {
            headers: {
                'Authorization': authHeader
            }
        });
        
        if (!response.ok) {
            throw new Error('Failed to load documents');
        }
        
        const documents = await response.json();
        displayDocuments(documents);
        
    } catch (error) {
        console.error('Load documents error:', error);
        showAlert(`Error: ${error.message}`, 'error');
    }
}

// Display Documents
function displayDocuments(documents) {
    const container = document.getElementById('documents-list');
    
    if (!documents || documents.length === 0) {
        container.innerHTML = '<p>No documents found. Upload a document to get started.</p>';
        return;
    }
    
    let html = '';
    documents.forEach(doc => {
        html += `
            <div class="document-card">
                <div class="document-header">
                    <div class="document-title">📄 ${doc.fileName}</div>
                    <span class="badge badge-success">ID: ${doc.id}</span>
                </div>
                <div class="document-meta">Jurisdiction: ${doc.jurisdiction}</div>
                <div class="document-meta">Version: ${doc.version}</div>
                <div class="document-meta">Created: ${new Date(doc.createdAt).toLocaleDateString()}</div>
                <div class="document-actions">
                    <button class="btn btn-primary" onclick="viewDocument(${doc.id})">View</button>
                    <button class="btn btn-secondary" onclick="analyzeDocument(${doc.id})">Analyze</button>
                </div>
            </div>
        `;
    });
    
    container.innerHTML = html;
}

// View Document
async function viewDocument(docId) {
    document.getElementById('analysis-doc-id').value = docId;
    showTab('analysis');
    loadAnalysis();
}

// Analyze Document
async function analyzeDocument(docId) {
    try {
        const response = await fetch(`${API_DOCS}/${docId}/analyze`, {
            method: 'POST',
            headers: {
                'Authorization': authHeader
            }
        });
        
        if (!response.ok) {
            throw new Error('Analysis failed');
        }
        
        const result = await response.json();
        document.getElementById('analysis-doc-id').value = docId;
        showTab('analysis');
        displayAnalysisResult(result);
        
    } catch (error) {
        console.error('Analysis error:', error);
        showAlert(`Error: ${error.message}`, 'error');
    }
}

// Load Analysis
async function loadAnalysis() {
    const docId = document.getElementById('analysis-doc-id').value;
    
    if (!docId) {
        showAlert('Please enter a document ID', 'warning');
        return;
    }
    
    try {
        const response = await fetch(`${API_DOCS}/${docId}/risk`, {
            headers: {
                'Authorization': authHeader
            }
        });
        
        if (!response.ok) {
            throw new Error('Failed to load analysis');
        }
        
        const result = await response.json();
        displayAnalysisResult(result);
        
    } catch (error) {
        console.error('Load analysis error:', error);
        showAlert(`Error: ${error.message}`, 'error');
    }
}

// Display Analysis Result
function displayAnalysisResult(result) {
    const container = document.getElementById('analysis-result');
    
    let html = `
        <div class="result-header">
            <h3>Risk Assessment</h3>
            <span class="badge badge-${getRiskScoreBadgeClass(result.overallRiskScore)}">
                Score: ${result.overallRiskScore}/10
            </span>
        </div>
        <p>${result.summary}</p>
    `;
    
    if (result.riskCategories && result.riskCategories.length > 0) {
        html += '<h4 style="margin-top: 20px;">Risk Categories</h4>';
        result.riskCategories.forEach(cat => {
            html += `
                <div class="risk-item">
                    <strong>${cat.category}</strong>: ${cat.score}/10<br>
                    ${cat.details}
                </div>
            `;
        });
    }
    
    if (result.criticalIssues && result.criticalIssues.length > 0) {
        html += '<h4 style="margin-top: 20px;">🚨 Critical Issues</h4><ul>';
        result.criticalIssues.forEach(issue => {
            html += `<li>${issue}</li>`;
        });
        html += '</ul>';
    }
    
    container.innerHTML = html;
}

// Helper Functions
function showLoading(action, show) {
    const btnText = document.getElementById(`${action}-btn-text`);
    const spinner = document.getElementById(`${action}-spinner`);
    
    if (show) {
        btnText.style.display = 'none';
        spinner.style.display = 'inline-block';
    } else {
        btnText.style.display = 'inline';
        spinner.style.display = 'none';
    }
}

function showAlert(message, type) {
    const alertClass = type === 'error' ? 'alert-error' : 
                       type === 'success' ? 'alert-success' : 'alert-warning';
    
    const alert = document.createElement('div');
    alert.className = `alert ${alertClass}`;
    alert.textContent = message;
    
    document.querySelector('.container').insertBefore(alert, document.querySelector('nav'));
    
    setTimeout(() => alert.remove(), 5000);
}

function getRiskBadgeClass(level) {
    switch(level?.toUpperCase()) {
        case 'HIGH': return 'danger';
        case 'LOW': return 'success';
        default: return 'warning';
    }
}

function getRiskScoreBadgeClass(score) {
    if (score >= 7) return 'danger';
    if (score >= 4) return 'warning';
    return 'success';
}

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    document.getElementById('user-info').textContent = '👤 Logged in as: lawyer';
});

