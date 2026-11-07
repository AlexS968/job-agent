package com.alexs.jobagent.batch.service;

public interface ReportGateway {
    /** Returns the provider message ID; an exception after submission is treated as uncertain. */
    String publish(String subject, String body);
}
