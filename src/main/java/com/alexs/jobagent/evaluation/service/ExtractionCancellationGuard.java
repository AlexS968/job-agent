package com.alexs.jobagent.evaluation.service;

import com.alexs.jobagent.evaluation.exception.ExtractionFailureException;
import org.springframework.stereotype.Service;

@Service
public class ExtractionCancellationGuard {
    public void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ExtractionFailureException("CANCELLED", "Extraction cancelled");
        }
    }
}
