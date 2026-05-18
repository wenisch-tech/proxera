package tech.wenisch.proxera.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.wenisch.proxera.bus.MessageBus;
import tech.wenisch.proxera.domain.AccessLog;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/admin/sse")
@Slf4j
public class AdminSseController {

    private final CopyOnWriteArrayList<SseEmitter> topologyEmitters  = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SseEmitter> globalLogEmitters  = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> routeLogEmitters = new ConcurrentHashMap<>();

    private final MessageBus messageBus;

    public AdminSseController(MessageBus messageBus) {
        this.messageBus = messageBus;

        // Subscribe to topology events and broadcast to all topology SSE emitters
        messageBus.subscribeTopology(event -> {
            var dead = new CopyOnWriteArrayList<SseEmitter>();
            topologyEmitters.forEach(emitter -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name(event.type())
                            .data(event, MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    dead.add(emitter);
                }
            });
            topologyEmitters.removeAll(dead);
        });
    }

    /**
     * SSE stream for real-time topology graph updates.
     */
    @GetMapping("/topology")
    public SseEmitter topologyStream() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        topologyEmitters.add(emitter);
        emitter.onCompletion(() -> topologyEmitters.remove(emitter));
        emitter.onTimeout(() -> topologyEmitters.remove(emitter));
        return emitter;
    }

    /**
     * SSE stream for live access log entries for a specific route.
     */
    @GetMapping("/routes/{id}/log")
    public SseEmitter routeLogStream(@PathVariable UUID id) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        routeLogEmitters.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeRouteEmitter(id, emitter));
        emitter.onTimeout(() -> removeRouteEmitter(id, emitter));
        return emitter;
    }

    /**
     * SSE stream for all access log entries across all routes (global live log).
     */
    @GetMapping("/log")
    public SseEmitter globalLogStream() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        globalLogEmitters.add(emitter);
        emitter.onCompletion(() -> globalLogEmitters.remove(emitter));
        emitter.onTimeout(() -> globalLogEmitters.remove(emitter));
        return emitter;
    }

    /**
     * Called when a new AccessLog entry is saved — pushes it to all SSE log subscribers.
     */
    @EventListener
    public void onAccessLog(AccessLog logEntry) {
        pushLogEntry(logEntry.getRouteId(), logEntry);
    }

    /**
     * Called by AccessLogService to push new log entries to subscribed route SSE clients.
     */
    public void pushLogEntry(UUID routeId, Object logEntry) {
        // Route-specific subscribers
        CopyOnWriteArrayList<SseEmitter> emitters = routeLogEmitters.get(routeId);
        if (emitters != null && !emitters.isEmpty()) {
            var dead = new CopyOnWriteArrayList<SseEmitter>();
            emitters.forEach(emitter -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("log")
                            .data(logEntry, MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    dead.add(emitter);
                }
            });
            emitters.removeAll(dead);
        }
        // Global log subscribers (topology live log overlay)
        if (!globalLogEmitters.isEmpty()) {
            var dead = new CopyOnWriteArrayList<SseEmitter>();
            globalLogEmitters.forEach(emitter -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name("log")
                            .data(logEntry, MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    dead.add(emitter);
                }
            });
            globalLogEmitters.removeAll(dead);
        }
    }

    private void removeRouteEmitter(UUID routeId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = routeLogEmitters.get(routeId);
        if (emitters != null) emitters.remove(emitter);
    }
}
