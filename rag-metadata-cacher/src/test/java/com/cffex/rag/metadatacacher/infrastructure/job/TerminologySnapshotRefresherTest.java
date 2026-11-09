package com.cffex.rag.metadatacacher.infrastructure.job;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cffex.rag.metadatacacher.application.DefaultTerminologyService;
import com.cffex.rag.metadatacacher.infrastructure.persistence.JdbcTerminologyStore;

@ExtendWith(MockitoExtension.class)
class TerminologySnapshotRefresherTest {

    @Test
    void refresh_reloadsFromStore() {
        JdbcTerminologyStore store = mock(JdbcTerminologyStore.class);
        DefaultTerminologyService service = mock(DefaultTerminologyService.class);
        when(store.loadAll()).thenReturn(List.of());
        TerminologySnapshotRefresher refresher = new TerminologySnapshotRefresher(store, service);

        refresher.refresh();

        verify(service).reload(anyList());
    }

    @Test
    void refresh_failureKeepsPreviousSnapshotSilently() {
        JdbcTerminologyStore store = mock(JdbcTerminologyStore.class);
        DefaultTerminologyService service = mock(DefaultTerminologyService.class);
        when(store.loadAll()).thenThrow(new RuntimeException("db down"));
        TerminologySnapshotRefresher refresher = new TerminologySnapshotRefresher(store, service);

        assertDoesNotThrow(refresher::refresh);

        verify(service, never()).reload(anyList());
        verify(service).recordRefreshFailure(org.mockito.ArgumentMatchers.any(RuntimeException.class));
    }

    @Test
    void startupRun_triggersWarmLoad() {
        JdbcTerminologyStore store = mock(JdbcTerminologyStore.class);
        DefaultTerminologyService service = mock(DefaultTerminologyService.class);
        when(store.loadAll()).thenReturn(List.of());
        TerminologySnapshotRefresher refresher = new TerminologySnapshotRefresher(store, service);

        assertDoesNotThrow(() -> refresher.run(null));

        verify(service).reload(anyList());
    }
}
