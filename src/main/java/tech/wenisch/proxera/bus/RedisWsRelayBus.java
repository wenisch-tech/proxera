package tech.wenisch.proxera.bus;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed {@link WsRelayBus} for multi-pod deployments.
 *
 * A single {@code pSubscribe} on the pattern {@code proxera:ws:*} is established at
 * startup.  Incoming messages are demultiplexed by channel suffix ({@code :a2c} /
 * {@code :c2a}) to the appropriate in-process handler map.
 *
 * Channel naming:
 * <pre>
 *   proxera:ws:{wsSessionId}:a2c   — agent-to-client direction
 *   proxera:ws:{wsSessionId}:c2a   — client-to-agent direction
 * </pre>
 *
 * wsSessionId is a UUID string (hyphens only, no colons) so suffix parsing is safe.
 *
 * Activated by {@link tech.wenisch.proxera.config.MessageBusConfig} when REDIS_HOST is set.
 */
@Slf4j
public class RedisWsRelayBus implements WsRelayBus {

    private static final String PREFIX = "proxera:ws:";
    private static final String SUFFIX_A2C = ":a2c";
    private static final String SUFFIX_C2A = ":c2a";
    private static final String PATTERN = PREFIX + "*";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ConcurrentHashMap<String, Consumer<WsRelayMessage>> a2cHandlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Consumer<WsRelayMessage>> c2aHandlers = new ConcurrentHashMap<>();

    public RedisWsRelayBus(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    private void startPatternSubscription() {
        redisTemplate.getConnectionFactory().getConnection()
                .pSubscribe((message, pattern) -> {
                    String channel = new String(message.getChannel());
                    try {
                        WsRelayMessage msg = objectMapper.readValue(
                                new String(message.getBody()), WsRelayMessage.class);
                        if (channel.endsWith(SUFFIX_A2C)) {
                            String id = channel.substring(PREFIX.length(), channel.length() - SUFFIX_A2C.length());
                            Consumer<WsRelayMessage> h = a2cHandlers.get(id);
                            if (h != null) h.accept(msg);
                        } else if (channel.endsWith(SUFFIX_C2A)) {
                            String id = channel.substring(PREFIX.length(), channel.length() - SUFFIX_C2A.length());
                            Consumer<WsRelayMessage> h = c2aHandlers.get(id);
                            if (h != null) h.accept(msg);
                        }
                    } catch (IOException e) {
                        log.warn("Failed to deserialize WsRelayMessage on channel {}: {}", channel, e.getMessage());
                    }
                }, PATTERN.getBytes());
        log.debug("Redis WsRelayBus pattern subscription started on {}", PATTERN);
    }

    @Override
    public void subscribeA2C(String wsSessionId, Consumer<WsRelayMessage> handler) {
        a2cHandlers.put(wsSessionId, handler);
    }

    @Override
    public void subscribeC2A(String wsSessionId, Consumer<WsRelayMessage> handler) {
        c2aHandlers.put(wsSessionId, handler);
    }

    @Override
    public void publishA2C(String wsSessionId, WsRelayMessage message) {
        publish(PREFIX + wsSessionId + SUFFIX_A2C, message);
    }

    @Override
    public void publishC2A(String wsSessionId, WsRelayMessage message) {
        publish(PREFIX + wsSessionId + SUFFIX_C2A, message);
    }

    private void publish(String channel, WsRelayMessage message) {
        try {
            redisTemplate.convertAndSend(channel, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            log.error("Failed to publish WsRelayMessage to {}: {}", channel, e.getMessage());
        }
    }

    @Override
    public void unsubscribe(String wsSessionId) {
        a2cHandlers.remove(wsSessionId);
        c2aHandlers.remove(wsSessionId);
    }
}
