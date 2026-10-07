package com.alamano.gateway.presence;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ProfessionalSessionRegistry {
    private final ConcurrentHashMap<String, Set<String>> sessionsByProfessional = new ConcurrentHashMap<>();

    public void register(String professionalId, String sessionId) {
        sessionsByProfessional.compute(professionalId, (id, sessions) -> {
            Set<String> current = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            current.add(sessionId);
            return current;
        });
    }

    public boolean unregister(String professionalId, String sessionId) {
        AtomicBoolean lastSession = new AtomicBoolean(false);
        sessionsByProfessional.computeIfPresent(professionalId, (id, sessions) -> {
            boolean existed = sessions.remove(sessionId);
            if (!existed) return sessions;
            if (sessions.isEmpty()) {
                lastSession.set(true);
                return null;
            }
            return sessions;
        });
        return lastSession.get();
    }

    public int sessionCount(String professionalId) {
        Set<String> sessions = sessionsByProfessional.get(professionalId);
        return sessions == null ? 0 : sessions.size();
    }
}
