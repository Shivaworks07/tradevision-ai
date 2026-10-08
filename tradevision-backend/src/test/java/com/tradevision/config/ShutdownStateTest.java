package com.tradevision.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies ShutdownState without a live Spring ApplicationContext: it implements
 * SmartLifecycle (so the shutdown flag flips early, before schedulers stop, rather than
 * late via @PreDestroy), stop() flips the flag, isRunning() correctly reflects
 * start()/stop(), and getPhase() returns the highest possible value -- the mechanism
 * SmartLifecycle's documented stop ordering (highest phase stops first) relies on to flip
 * this flag before the scheduler infrastructure (or any other default-phase Lifecycle bean)
 * gets its own stop() called.
 */
class ShutdownStateTest {

    @Test
    void isSmartLifecycle_notPreDestroyBased() {
        assertThat(new ShutdownState()).isInstanceOf(org.springframework.context.SmartLifecycle.class);
    }

    @Test
    void initialState_notShuttingDown_notRunning() {
        var state = new ShutdownState();

        assertThat(state.isShuttingDown()).isFalse();
        assertThat(state.isRunning()).isFalse();
    }

    @Test
    void start_marksRunning_stillNotShuttingDown() {
        var state = new ShutdownState();

        state.start();

        assertThat(state.isRunning()).isTrue();
        assertThat(state.isShuttingDown()).isFalse();
    }

    @Test
    void stop_flipsShuttingDownAndClearsRunning() {
        var state = new ShutdownState();
        state.start();

        state.stop();

        assertThat(state.isShuttingDown()).isTrue();
        assertThat(state.isRunning()).isFalse();
    }

    @Test
    void isAutoStartup_true_soSpringActuallyCallsStartOnContextRefresh() {
        assertThat(new ShutdownState().isAutoStartup()).isTrue();
    }

    @Test
    void getPhase_isMaxValue_soThisStopsBeforeEveryDefaultPhaseLifecycleBeanIncludingSchedulers() {
        assertThat(new ShutdownState().getPhase()).isEqualTo(Integer.MAX_VALUE);
    }
}
