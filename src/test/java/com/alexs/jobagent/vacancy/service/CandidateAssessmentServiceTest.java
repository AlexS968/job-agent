package com.alexs.jobagent.vacancy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alexs.jobagent.vacancy.repository.CandidateAssessmentRepository;
import com.alexs.jobagent.vacancy.repository.CandidateAssessmentRepository.CandidateInput;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CandidateAssessmentServiceTest {
    @Test
    void staleInputAndConcurrentRecordAreSkippedWithoutCountingThem() {
        var repository = mock(CandidateAssessmentRepository.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenAnswer(call -> new SimpleTransactionStatus());
        when(repository.pending(CandidateAssessmentPolicy.VERSION, CandidateAssessmentService.MAX_PER_INVOCATION))
                .thenReturn(List.of(1L, 2L, 3L));
        when(repository.lockCurrent(2L)).thenReturn(new CandidateInput(20L, "Java Engineer", null));
        when(repository.lockCurrent(3L)).thenReturn(new CandidateInput(30L, "Java Engineer", null));
        when(repository.record(eq(3L), eq(30L), eq(CandidateAssessmentPolicy.VERSION), any()))
                .thenReturn(true);

        var result = new CandidateAssessmentService(repository, new CandidateAssessmentPolicy(), manager)
                .assessPending();

        assertEquals(1, result.observed());
        assertEquals(1, result.candidates());
        assertEquals(0, result.review());
        assertEquals(0, result.notCandidates());
        verify(repository, never()).record(eq(1L), anyLong(), any(), any());
        verify(repository).record(eq(2L), eq(20L), eq(CandidateAssessmentPolicy.VERSION), any());
        verify(repository).record(eq(3L), eq(30L), eq(CandidateAssessmentPolicy.VERSION), any());
    }
}
