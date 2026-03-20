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

        java.nio.file.Path tempRepoPath = null;
        try {
            tempRepoPath = java.nio.file.Files.createTempDirectory("legal-ai-diff-");
            File repoDir = tempRepoPath.toFile();

            try (Git git = Git.init().setDirectory(repoDir).call()) {
                Repository repository = git.getRepository();
                File docFile = new File(repoDir, "document.txt");

                // Commit 1: old content
                java.nio.file.Files.writeString(docFile.toPath(), oldContent, java.nio.charset.StandardCharsets.UTF_8);
                git.add().addFilepattern("document.txt").call();
                RevCommit oldCommit = git.commit()
                        .setMessage("version-old")
                        .setAuthor("legal-ai", "legal@ai.system")
                        .call();

                // Commit 2: new content
                java.nio.file.Files.writeString(docFile.toPath(), newContent, java.nio.charset.StandardCharsets.UTF_8);
                git.add().addFilepattern("document.txt").call();
                RevCommit newCommit = git.commit()
                        .setMessage("version-new")
                        .setAuthor("legal-ai", "legal@ai.system")
                        .call();

                // Generate unified diff between the two commits
                ByteArrayOutputStream diffOutput = new ByteArrayOutputStream();
                try (DiffFormatter formatter = new DiffFormatter(diffOutput)) {
                    formatter.setRepository(repository);

                    try (ObjectReader reader = repository.newObjectReader()) {
                        CanonicalTreeParser oldTreeIter = new CanonicalTreeParser();
                        oldTreeIter.reset(reader, oldCommit.getTree().getId());

                        CanonicalTreeParser newTreeIter = new CanonicalTreeParser();
                        newTreeIter.reset(reader, newCommit.getTree().getId());

                        java.util.List<DiffEntry> diffs = formatter.scan(oldTreeIter, newTreeIter);
                        formatter.format(diffs);
                        formatter.flush();
                    }
                }

                return diffOutput.toString(java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            logger.error("JGit diff computation failed, falling back to summary: {}", e.getMessage());
            return "=== Diff failed: " + e.getMessage() + "\nOld length: " + oldContent.length() +
                   " chars\nNew length: " + newContent.length() + " chars";
        } finally {
            if (tempRepoPath != null) {
                deleteDirectory(tempRepoPath.toFile());
            }
        }
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) deleteDirectory(f);
            }
        }
        dir.delete();
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

