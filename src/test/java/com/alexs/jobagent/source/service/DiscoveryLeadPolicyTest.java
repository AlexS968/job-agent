package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DiscoveryLeadPolicyTest {
    @Test
    void distinguishesAtsAggregatorVendorAndEmployerByExactHostBoundary() {
        assertEquals(DiscoveryLeadPolicy.Kind.ATS,
                DiscoveryLeadPolicy.classify("https://job-boards.greenhouse.io/acme/jobs/42"));
        assertEquals(DiscoveryLeadPolicy.Kind.ATS,
                DiscoveryLeadPolicy.classify("https://jobs.lever.co/acme/42"));
        assertEquals(DiscoveryLeadPolicy.Kind.NON_EMPLOYER,
                DiscoveryLeadPolicy.classify("https://job-boards.greenhouse.io/"));
        assertEquals(DiscoveryLeadPolicy.Kind.NON_EMPLOYER,
                DiscoveryLeadPolicy.classify("https://jobs.lever.co/Bad_ID"));
        assertEquals(DiscoveryLeadPolicy.Kind.AGGREGATOR,
                DiscoveryLeadPolicy.classify("https://en.devjobs.at/jobs/42"));
        assertEquals(DiscoveryLeadPolicy.Kind.NON_EMPLOYER,
                DiscoveryLeadPolicy.classify("https://www.greenhouse.com/"));
        assertEquals(DiscoveryLeadPolicy.Kind.NON_EMPLOYER,
                DiscoveryLeadPolicy.classify("https://tavily.com/"));
        assertEquals(DiscoveryLeadPolicy.Kind.EMPLOYER,
                DiscoveryLeadPolicy.classify("https://careers.greenhouse.com.example/jobs"));
        assertEquals(DiscoveryLeadPolicy.Kind.EMPLOYER,
                DiscoveryLeadPolicy.classify("https://mydevjobs.at.example/careers"));
        assertEquals(DiscoveryLeadPolicy.Kind.EMPLOYER,
                DiscoveryLeadPolicy.classify("https://careers.notgreenhouse.io/jobs"));
    }
}
