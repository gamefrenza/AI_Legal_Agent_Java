package com.legalai.agent.repository;

import com.legalai.agent.entity.Document;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends CrudRepository<Document, Long> {

    /**
     * Finds all documents by jurisdiction
     * 
     * @param jurisdiction The jurisdiction to search for
     * @return List of documents matching the jurisdiction
     */
    List<Document> findByJurisdiction(String jurisdiction);

    /**
     * Finds a document by file name and specific version
     * 
     * @param fileName The file name to search for
     * @param version The version number
     * @return Optional containing the document if found
     */
    Optional<Document> findByFileNameAndVersion(String fileName, Integer version);

    /**
     * Retrieves the latest version of a document by file name
     * Uses custom JPQL query to find the document with the maximum version number
     * 
     * @param fileName The file name to search for
     * @return Optional containing the latest version document if found
     */
    @Query("SELECT d FROM Document d WHERE d.fileName = :fileName " +
           "AND d.version = (SELECT MAX(d2.version) FROM Document d2 WHERE d2.fileName = :fileName)")
    Optional<Document> findLatestVersionByFileName(@Param("fileName") String fileName);

    /**
     * Alternative method to get the latest version using ordering and limit
     * Finds the most recent document by file name, ordered by version descending
     * 
     * @param fileName The file name to search for
     * @return Optional containing the latest version document if found
     */
    Optional<Document> findFirstByFileNameOrderByVersionDesc(String fileName);

    /**
     * Finds all versions of a document by file name, ordered by version descending
     * 
     * @param fileName The file name to search for
     * @return List of all document versions, newest first
     */
    List<Document> findByFileNameOrderByVersionDesc(String fileName);
}

