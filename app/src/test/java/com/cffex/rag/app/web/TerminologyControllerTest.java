package com.cffex.rag.app.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.cffex.rag.common.domain.terminology.TerminologyStatus;
import com.cffex.rag.common.service.TerminologyService;
import com.cffex.rag.metadatacacher.infrastructure.job.TerminologySnapshotRefresher;

class TerminologyControllerTest {

    @Test
    void status_returnsServiceStatus() {
        TerminologyService service = mock(TerminologyService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TerminologySnapshotRefresher> provider = mock(ObjectProvider.class);
        TerminologyStatus status = loadedStatus();
        when(service.status()).thenReturn(status);

        TerminologyStatus actual = new TerminologyController(service, provider).status();

        assertEquals(status, actual);
    }

    @Test
    void refresh_triggersImmediateReloadAndReturnsLatestStatus() {
        TerminologyService service = mock(TerminologyService.class);
        TerminologySnapshotRefresher refresher = mock(TerminologySnapshotRefresher.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TerminologySnapshotRefresher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(refresher);
        when(service.status()).thenReturn(loadedStatus());

        new TerminologyController(service, provider).refresh();

        verify(refresher).refresh();
    }

    private static TerminologyStatus loadedStatus() {
        return new TerminologyStatus(true, true, 1, Instant.now(), Instant.now(), 1, 1, 1, null);
    }
}
