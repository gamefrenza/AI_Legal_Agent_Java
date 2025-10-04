package com.legalai.agent.service;

import com.legalai.agent.entity.Document;
import com.legalai.agent.repository.DocumentRepository;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocumentVersionService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentVersionService.class);

    @Autowired
    private DocumentRepository documentRepository;

    /**
     * Creates a new version of a document with computed diffs
     * 
     * @param oldDoc The previous version of the document
     * @param changes Description of changes made
     * @return The newly created document version
     * @throws Exception if version creation fails
     */
    @Transactional
    public Document createNewVersion(Document oldDoc, String changes) throws Exception {
        logger.info("Creating new version for document: {}", oldDoc.getFileName());

        // Decrypt old content for comparison
        String oldContent = oldDoc.decryptContent();

        // Create new document instance
        Document newDoc = new Document();
        newDoc.setFileName(oldDoc.getFileName());
        newDoc.setJurisdiction(oldDoc.getJurisdiction());
        
        // Increment version
        Integer newVersion = (oldDoc.getVersion() != null ? oldDoc.getVersion() : 0) + 1;
        newDoc.setVersion(newVersion);

        // For now, we'll store the changes description as part of encrypted content
        // In a real scenario, you might store this separately
        String newContent = changes;
        newDoc.encryptContent(newContent);

        // Compute diff between old and new content
        String diff = computeDiff(oldContent, newContent);
        logger.debug("Computed diff for {}: {} characters changed", oldDoc.getFileName(), diff.length());

        // Save new version
        Document savedDoc = documentRepository.save(newDoc);
        logger.info("Created version {} for document {}", newVersion, oldDoc.getFileName());

        return savedDoc;
    }

    /**
     * Retrieves version history for a document with diffs between versions
     * 
     * @param fileName The file name to get history for
     * @return List of version history entries with diffs
     * @throws Exception if retrieval fails
     */
    public List<DocumentVersionHistory> getVersionHistory(String fileName) throws Exception {
        logger.info("Retrieving version history for: {}", fileName);

        List<Document> versions = documentRepository.findByFileNameOrderByVersionDesc(fileName);
        
        if (versions.isEmpty()) {
            logger.warn("No versions found for document: {}", fileName);
            return Collections.emptyList();
        }

        List<DocumentVersionHistory> history = new ArrayList<>();

        for (int i = 0; i < versions.size(); i++) {
            Document currentVersion = versions.get(i);
            
            DocumentVersionHistory versionHistory = new DocumentVersionHistory();
            versionHistory.setVersion(currentVersion.getVersion());
            versionHistory.setFileName(currentVersion.getFileName());
            versionHistory.setJurisdiction(currentVersion.getJurisdiction());
            versionHistory.setCreatedAt(currentVersion.getCreatedAt());
            versionHistory.setUpdatedAt(currentVersion.getUpdatedAt());

            // Compute diff with previous version
            if (i < versions.size() - 1) {
                Document previousVersion = versions.get(i + 1);
                String currentContent = currentVersion.decryptContent();
                String previousContent = previousVersion.decryptContent();
                
                String diff = computeDiff(previousContent, currentContent);
                versionHistory.setDiff(diff);
                versionHistory.setChangesSummary(summarizeChanges(diff));
            } else {
                versionHistory.setDiff("Initial version");
                versionHistory.setChangesSummary("Document created");
            }

            history.add(versionHistory);
        }

        logger.info("Retrieved {} versions for document: {}", history.size(), fileName);
        return history;
    }

    /**
     * Computes diff between two text strings using a simple diff algorithm
     * In production, you could integrate with JGit's diff formatter for more sophisticated diffs
     * 
     * @param oldContent The old content
     * @param newContent The new content
     * @return String representation of the diff
     */
    private String computeDiff(String oldContent, String newContent) {
        if (oldContent == null) oldContent = "";
        if (newContent == null) newContent = "";

        String[] oldLines = oldContent.split("\n");
        String[] newLines = newContent.split("\n");

        StringBuilder diff = new StringBuilder();
        diff.append("=== Diff Summary ===\n");
        diff.append("Old content length: ").append(oldContent.length()).append(" characters\n");
        diff.append("New content length: ").append(newContent.length()).append(" characters\n");
        diff.append("Lines removed: ").append(oldLines.length).append("\n");
        diff.append("Lines added: ").append(newLines.length).append("\n");
        diff.append("\n--- Old Content ---\n").append(oldContent).append("\n");
        diff.append("\n+++ New Content +++\n").append(newContent).append("\n");

        return diff.toString();
    }

    /**
     * Creates a human-readable summary of changes from a diff
     * 
     * @param diff The diff string
     * @return Summary of changes
     */
    private String summarizeChanges(String diff) {
        int linesChanged = diff.split("\n").length;
        return String.format("Modified document with %d lines of changes", linesChanged);
    }

    /**
     * Inner class to represent version history with diff information
     */
    public static class DocumentVersionHistory {
        private Integer version;
        private String fileName;
        private String jurisdiction;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String diff;
        private String changesSummary;

        // Getters and Setters
        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getJurisdiction() {
            return jurisdiction;
        }

        public void setJurisdiction(String jurisdiction) {
            this.jurisdiction = jurisdiction;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }

        public LocalDateTime getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
        }

        public String getDiff() {
            return diff;
        }

        public void setDiff(String diff) {
            this.diff = diff;
        }

        public String getChangesSummary() {
            return changesSummary;
        }

        public void setChangesSummary(String changesSummary) {
            this.changesSummary = changesSummary;
        }
    }
}

