package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VariantAnalysisStartupRecoveryTest {

    @Test
    void recoversProvenSameJobCheckpointBeforeFailingOtherInterruptedJobs() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        ExomiserCompletionRecoveryService exomiserRecovery = mock(ExomiserCompletionRecoveryService.class);
        when(repository.failInterruptedJobs()).thenReturn(2);
        when(exomiserRecovery.recoverInterruptedExomiserFinalization()).thenReturn(1);

        new VariantAnalysisStartupRecovery(repository, exomiserRecovery).run(null);

        InOrder recoveryOrder = inOrder(exomiserRecovery, repository);
        recoveryOrder.verify(exomiserRecovery).recoverInterruptedExomiserFinalization();
        recoveryOrder.verify(repository).failInterruptedJobs();
    }

    @Test
    void historicalJobLockTimeoutDoesNotAbortApplicationStartup() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.failInterruptedJobs()).thenThrow(new CannotAcquireLockException("locked historical job"));

        assertDoesNotThrow(() -> new VariantAnalysisStartupRecovery(repository).run(null));

        verify(repository).failInterruptedJobs();
    }

    @Test
    void transientCheckpointReadFailureDoesNotFailUnexaminedJobs() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        ExomiserCompletionRecoveryService exomiserRecovery = mock(ExomiserCompletionRecoveryService.class);
        when(exomiserRecovery.recoverInterruptedExomiserFinalization())
                .thenThrow(new CannotAcquireLockException("checkpoint read unavailable"));

        assertDoesNotThrow(() -> new VariantAnalysisStartupRecovery(repository, exomiserRecovery).run(null));

        verify(repository, never()).failInterruptedJobs();
    }

    @Test
    void invalidDatabaseSchemaStillFailsStartup() {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.failInterruptedJobs())
                .thenThrow(new BadSqlGrammarException("recovery", "invalid schema", new SQLException()));

        assertThrows(BadSqlGrammarException.class,
                () -> new VariantAnalysisStartupRecovery(repository).run(null));
    }
}
