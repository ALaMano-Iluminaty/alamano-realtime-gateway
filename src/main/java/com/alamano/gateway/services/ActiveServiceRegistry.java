package com.alamano.gateway.services;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Servicios en curso conocidos por esta instancia, alimentados por service.status.changed.
 * Vive en memoria: se pierde al reiniciar y se vuelve a llenar con el siguiente cambio de estado de cada servicio.
 */
@Component
public class ActiveServiceRegistry {
    public static final int DEFAULT_VERSION_CAPACITY = 10_000;
    private static final Logger log = LoggerFactory.getLogger(ActiveServiceRegistry.class);
    private static final Set<String> ACTIVE_STATUSES = Set.of("RESERVED", "EN_ROUTE", "ARRIVED", "IN_PROGRESS");
    private static final Set<String> TERMINAL_STATUSES = Set.of("COMPLETED", "CANCELLED");

    private final ConcurrentHashMap<String, ActiveService> servicesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> serviceByProfessional = new ConcurrentHashMap<>();
    // Última versión vista de cada servicio, incluso terminado, para ignorar eventos viejos que lleguen tarde.
    private final Map<String, Long> lastVersions;

    public ActiveServiceRegistry() {
        this(DEFAULT_VERSION_CAPACITY);
    }

    ActiveServiceRegistry(int versionCapacity) {
        int safeCapacity = Math.max(1, versionCapacity);
        this.lastVersions = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > safeCapacity;
            }
        });
    }

    public void apply(String serviceId, String professionalId, String clientId, String status, long version) {
        boolean active = ACTIVE_STATUSES.contains(status);
        if (!active && !TERMINAL_STATUSES.contains(status)) {
            log.warn("Estado de servicio desconocido {} para el servicio {}: se ignora.", status, serviceId);
            return;
        }
        // compute bloquea la clave serviceId: cada cambio de este servicio (y de su vendedor) ocurre aquí dentro, en orden.
        servicesById.compute(serviceId, (id, current) -> {
            Long lastVersion = lastVersions.get(id);
            if (lastVersion == null && current != null) lastVersion = current.version();
            if (lastVersion != null && version <= lastVersion) {
                log.debug("Evento de servicio {} ignorado: versión {} no es mayor que {}.", id, version, lastVersion);
                return current;
            }
            lastVersions.put(id, version);
            if (current != null && !current.professionalId().equals(professionalId)) {
                serviceByProfessional.remove(current.professionalId(), id);
            }
            if (!active) {
                serviceByProfessional.remove(professionalId, id);
                return null;
            }
            serviceByProfessional.put(professionalId, id);
            return new ActiveService(id, professionalId, clientId, status, version);
        });
    }

    public Optional<ActiveService> findByProfessional(String professionalId) {
        String serviceId = serviceByProfessional.get(professionalId);
        if (serviceId == null) return Optional.empty();
        return findByService(serviceId).filter(service -> service.professionalId().equals(professionalId));
    }

    public Optional<ActiveService> findByService(String serviceId) {
        return Optional.ofNullable(servicesById.get(serviceId));
    }
}
