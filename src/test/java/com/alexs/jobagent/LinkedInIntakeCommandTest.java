package com.alexs.jobagent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alexs.jobagent.source.dto.LinkedInIntakeResultDTO;
import com.alexs.jobagent.source.service.LinkedInIntakeService;
import com.alexs.jobagent.source.service.LinkedInRepairService;
import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class LinkedInIntakeCommandTest {
    private final Flyway flyway = mock(Flyway.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApplicationContext context = mock(ApplicationContext.class);
    private final LinkedInIntakeService intake = mock(LinkedInIntakeService.class);
    private final CommandLineRunner runner = new JobAgentApplication().verifyDatabase(
            flyway, jdbc, null, new JsonMapper(), null, null, null, "", null, "", null,
            null, null, null, null, null, context);

    LinkedInIntakeCommandTest() {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Integer.class))).thenReturn(0);
        when(context.getBean(LinkedInIntakeService.class)).thenReturn(intake);
    }

    @Test
    void boundedModeUsesExplicitDatesAndDoesNotResolveOtherConditionalBeans() throws Exception {
        when(intake.run(4, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 18)))
                .thenReturn(new LinkedInIntakeResultDTO(2, 1, 1, 0, 0, 0));
        assertDoesNotThrow(() -> runner.run("--mode=linkedin-intake", "--max-messages=4",
                "--after-date=2026-11-09", "--before-date=2026-11-18"));
        verify(intake).run(4, LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 18));
        verify(flyway).validate();
    }

    @Test
    void reviewRequiredFailsAfterTheBoundedIntakeCompletes() {
        when(intake.run(10, null, null)).thenReturn(new LinkedInIntakeResultDTO(2, 0, 1, 1, 0, 1));
        assertEquals("LinkedIn intake has messages requiring review", assertThrows(IllegalStateException.class,
                () -> runner.run("--mode=linkedin-intake")).getMessage());
        verify(intake).run(10, null, null);
    }

    @Test
    void invalidLimitsOptionsAndDatesStopBeforeIntake() {
        assertThrows(IllegalArgumentException.class,
                () -> runner.run("--mode=linkedin-intake", "--max-messages=0"));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run("--mode=linkedin-intake", "--max-messages=21"));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run("--mode=linkedin-intake", "--after-date=invalid"));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run("--mode=linkedin-intake", "--after-date=2026-11-09",
                        "--after-date=2026-11-10"));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run("--mode=linkedin-intake", "--unexpected=yes"));
        verifyNoInteractions(intake);
    }

    @Test
    void repairUsesPrivateSelectionAndFailsIfReviewRemains() throws Exception {
        repairSelection("sample-first,sample-second");
        var repair = mock(LinkedInRepairService.class);
        when(context.getBean(LinkedInRepairService.class)).thenReturn(repair);
        var ids = List.of("sample-first", "sample-second");
        when(repair.repair(ids)).thenReturn(new LinkedInRepairService.Result(2, 1, 0, 1, 1),
                new LinkedInRepairService.Result(2, 0, 1, 1, 0));

        runner.run("--mode=linkedin-repair");
        assertEquals("LinkedIn repair has messages requiring review", assertThrows(IllegalStateException.class,
                () -> runner.run("--mode=linkedin-repair")).getMessage());
        verify(repair, times(2)).repair(ids);
        verifyNoInteractions(intake);
    }

    @Test
    void repairRejectsMissingOrOversizedConfigurationAndExtraOptions() {
        repairSelection(null);
        assertThrows(IllegalArgumentException.class, () -> runner.run("--mode=linkedin-repair"));
        repairSelection("a".repeat(651));
        assertThrows(IllegalArgumentException.class, () -> runner.run("--mode=linkedin-repair"));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run("--mode=linkedin-repair", "--JOBAGENT_LINKEDIN_REPAIR_IDS=sample-id"));
        verify(context, never()).getBean(LinkedInRepairService.class);
    }

    private void repairSelection(String selection) {
        var environment = new MockEnvironment();
        if (selection != null) {
            environment.setProperty("JOBAGENT_LINKEDIN_REPAIR_IDS", selection);
        }
        when(context.getEnvironment()).thenReturn(environment);
    }
}
