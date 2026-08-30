package com.guildworkman.api.discovery.reputation;

import com.guildworkman.api.discovery.reputation.WorkerReputationSnapshot.ReputationSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReputationSnapshotServiceTest {

    private WorkerReputationSnapshotRepository snapshots;
    private ReputationContractClient client;
    private ReputationProperties properties;
    private ReputationSnapshotService service;

    @BeforeEach
    void setUp() {
        snapshots = mock(WorkerReputationSnapshotRepository.class);
        client = mock(ReputationContractClient.class);
        properties = new ReputationProperties();
        properties.setFallbackScore(0.5);
        properties.setShrinkK(5);
        service = new ReputationSnapshotService(snapshots, client, properties);
        when(snapshots.findById(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void writesAnOnchainSnapshotFromTheAggregate() {
        when(client.fetchRating(7L)).thenReturn(Optional.of(new RatingAggregate(20, 4.5)));

        service.refreshOne(7L);

        WorkerReputationSnapshot saved = capture();
        assertThat(saved.getWorkerId()).isEqualTo(7L);
        assertThat(saved.getSource()).isEqualTo(ReputationSource.ONCHAIN);
        assertThat(saved.getRatingCount()).isEqualTo(20);
        assertThat(saved.getReputationScore()).isBetween(0.8, 0.92); // ~0.9 shrunk slightly toward 0.5
        assertThat(saved.getRefreshedAt()).isNotNull();
    }

    @Test
    void aKnownUnratedWorkerGetsANeutralOnchainSnapshot() {
        when(client.fetchRating(7L)).thenReturn(Optional.empty());

        service.refreshOne(7L);

        WorkerReputationSnapshot saved = capture();
        assertThat(saved.getSource()).isEqualTo(ReputationSource.ONCHAIN);
        assertThat(saved.getReputationScore()).isEqualTo(0.5);
        assertThat(saved.getRatingCount()).isZero();
    }

    @Test
    void writesAFallbackSnapshotWhenTheReadModelFailsAndThereIsNoPriorSnapshot() {
        when(snapshots.existsById(7L)).thenReturn(false);

        boolean wroteFallback = service.writeFallbackIfAbsent(7L);

        assertThat(wroteFallback).isTrue();
        WorkerReputationSnapshot saved = capture();
        assertThat(saved.getSource()).isEqualTo(ReputationSource.FALLBACK);
        assertThat(saved.getReputationScore()).isEqualTo(0.5);
    }

    @Test
    void keepsAnExistingSnapshotWhenTheReadModelFails() {
        when(snapshots.existsById(7L)).thenReturn(true);

        boolean wroteFallback = service.writeFallbackIfAbsent(7L);

        assertThat(wroteFallback).isFalse();
        verify(snapshots, never()).save(any());
    }

    private WorkerReputationSnapshot capture() {
        ArgumentCaptor<WorkerReputationSnapshot> captor = ArgumentCaptor.forClass(WorkerReputationSnapshot.class);
        verify(snapshots).save(captor.capture());
        return captor.getValue();
    }
}
