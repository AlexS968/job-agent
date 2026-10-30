package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alexs.jobagent.evaluation.dto.ExtractionClaimDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionOutputDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionRejectionDTO;
import com.alexs.jobagent.evaluation.dto.ExtractionResultDTO;
import com.alexs.jobagent.evaluation.exception.PartialExtractionException;
import com.alexs.jobagent.evaluation.service.ExtractionPilotService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionBatchCommandTest {
    private final JobAgentApplication application = new JobAgentApplication();
    private final ExtractionPilotService extraction = mock(ExtractionPilotService.class);
    private final String[] args = {"--mode=extract-primary", "--vacancy-id=1", "--live-confirm=yes",
            "--pricing-reviewed-on=2026-10-29"};

    @Test
    void partialHasDistinctExitCodeWhileFullSuccessAndNoValidClaimsRemainDistinct() {
        ExtractionOutputDTO accepted = new ExtractionOutputDTO(List.of(new ExtractionClaimDTO(
                "TITLE", "Backend Engineer", "title", "Backend Engineer", "STATED")));
        when(extraction.extract(eq(1L), eq(false), eq(false), eq(true), eq("2026-10-29")))
                .thenReturn(new ExtractionResultDTO(1, "PARTIAL", "synthetic-model", 1, "revision",
                        accepted, false, 1, 1, List.of(new ExtractionRejectionDTO(2, "INVALID_FACT_VALUE"))))
                .thenReturn(new ExtractionResultDTO(1, "SUCCEEDED", "synthetic-model", 1, "revision",
                        accepted, true))
                .thenReturn(new ExtractionResultDTO(1, "FAILED", "synthetic-model", 1, "revision",
                        new ExtractionOutputDTO(List.of()), false, 0, 1,
                        List.of(new ExtractionRejectionDTO(0, "UNSUPPORTED_FACT"))));
        PartialExtractionException partial = assertThrows(PartialExtractionException.class,
                () -> application.runExtraction(args, extraction, false));
        assertEquals(2, JobAgentApplication.exitCode(partial));
        assertDoesNotThrow(() -> application.runExtraction(args, extraction, false));
        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> application.runExtraction(args, extraction, false));
        assertEquals(1, JobAgentApplication.exitCode(failed));
        verify(extraction, times(3)).extract(anyLong(), anyBoolean(), anyBoolean(), anyBoolean(), anyString());
    }
}
