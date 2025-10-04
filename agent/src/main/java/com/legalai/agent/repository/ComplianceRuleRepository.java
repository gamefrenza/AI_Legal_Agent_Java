package com.legalai.agent.repository;

import com.legalai.agent.entity.ComplianceRule;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ComplianceRuleRepository extends CrudRepository<ComplianceRule, Long> {

    /**
     * Finds all compliance rules for a specific jurisdiction
     * 
     * @param jurisdiction The jurisdiction to search for
     * @return List of compliance rules matching the jurisdiction
     */
    List<ComplianceRule> findByJurisdiction(String jurisdiction);

    /**
     * Finds all active compliance rules for a jurisdiction
     * 
     * @param jurisdiction The jurisdiction to search for
     * @param active Whether the rule is active
     * @return List of active compliance rules
     */
    List<ComplianceRule> findByJurisdictionAndActive(String jurisdiction, Boolean active);

    /**
     * Finds a specific compliance rule by name and jurisdiction
     * 
     * @param ruleName The name of the rule
     * @param jurisdiction The jurisdiction
     * @return List of matching rules
     */
    List<ComplianceRule> findByRuleNameAndJurisdiction(String ruleName, String jurisdiction);

    /**
     * Finds all active compliance rules
     * 
     * @param active Whether the rule is active
     * @return List of all active rules
     */
    List<ComplianceRule> findByActive(Boolean active);

    /**
     * Finds rules by severity level
     * 
     * @param severity The severity level (e.g., "HIGH", "MEDIUM", "LOW")
     * @return List of rules with specified severity
     */
    List<ComplianceRule> findBySeverity(String severity);
}

