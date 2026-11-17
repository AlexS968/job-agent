package com.alexs.jobagent.vacancy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CandidateAssessmentPolicyTest {
    private final CandidateAssessmentPolicy policy = new CandidateAssessmentPolicy();

    @Test
    void genericTitleNeedsBothJavaStackAndBackendWorkInDescription() {
        assertEquals("CANDIDATE", policy.assess("Software Engineer",
                "<section>Build Java backend services and REST APIs.</section>").decision());
        assertEquals("REVIEW", policy.assess("Software Engineer", "Experience with Kafka is useful.").decision());
        assertEquals("REVIEW", policy.assess("Software Engineer", "Build backend services.").decision());
        assertEquals("REVIEW", policy.assess("Software Engineer",
                "Java is not required; work on backend systems.").decision());
    }

    @Test
    void directRoleAndUnrelatedRoleRemainDistinctFromProfileEligibility() {
        assertEquals("CANDIDATE", policy.assess("Senior Java Engineer", null).decision());
        assertEquals("REVIEW", policy.assess("Senior Backend Engineer", null).decision());
        assertEquals("CANDIDATE", policy.assess("Backend Engineer", "Build Java services.").decision());
        assertEquals("REVIEW", policy.assess("Backend Engineer", "Build Python services.").decision());
        assertEquals("NOT_CANDIDATE", policy.assess("Credit Risk Analyst",
                "Work with Java developers on backend systems.").decision());
        assertEquals("REVIEW", policy.assess(null, "Java backend services").decision());
    }
}
