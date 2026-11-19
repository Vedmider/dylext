package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.SessionUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic session eviction (TTL sweep). Disable with dylext.session.sweep.enabled=false. */
@Component
@ConditionalOnProperty(name = "dylext.session.sweep.enabled", havingValue = "true",
        matchIfMissing = true)
public class SessionMaintenance {

    private final SessionUseCase session;

    public SessionMaintenance(SessionUseCase session) {
        this.session = session;
    }

    @Scheduled(fixedDelayString = "${dylext.session.sweep.interval-ms:3600000}")
    public void sweep() {
        session.purge();
    }
}
