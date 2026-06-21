package tech.wenisch.proxera.config;

import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Runtime Redis infrastructure for multi-pod deployments.
 *
 * Native-image AOT can evaluate Spring conditions at build time. Keeping this
 * bean unconditional lets the deployed runtime decide from REDIS_HOST.
 */
@Component
@Slf4j
public class RedisRuntimeService implements SmartLifecycle, DisposableBean {

    private final boolean available;
    private final LettuceConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer listenerContainer;

    private volatile boolean running;

    public RedisRuntimeService(Environment environment) {
        String host = environment.getProperty("REDIS_HOST", "").trim();
        if (host.isEmpty()) {
            this.available = false;
            this.connectionFactory = null;
            this.redisTemplate = null;
            this.listenerContainer = null;
            return;
        }

        int port = Integer.parseInt(environment.getProperty("REDIS_PORT", "6379").trim());
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        String password = environment.getProperty("REDIS_PASSWORD", "").trim();
        if (!password.isEmpty()) {
            config.setPassword(RedisPassword.of(password));
        }

        this.connectionFactory = new LettuceConnectionFactory(config);
        this.connectionFactory.afterPropertiesSet();

        this.redisTemplate = new StringRedisTemplate(connectionFactory);
        this.redisTemplate.afterPropertiesSet();

        this.listenerContainer = new RedisMessageListenerContainer();
        this.listenerContainer.setConnectionFactory(connectionFactory);
        this.listenerContainer.afterPropertiesSet();

        this.available = true;
        log.info("Redis runtime enabled, host={}, port={}", host, port);
    }

    public boolean isAvailable() {
        return available;
    }

    public StringRedisTemplate redisTemplate() {
        if (!available) {
            throw new IllegalStateException("Redis is not configured");
        }
        return redisTemplate;
    }

    public RedisMessageListenerContainer listenerContainer() {
        if (!available) {
            throw new IllegalStateException("Redis is not configured");
        }
        return listenerContainer;
    }

    @Override
    public void start() {
        if (available && !running) {
            listenerContainer.start();
            running = true;
        }
    }

    @Override
    public void stop() {
        if (available && running) {
            listenerContainer.stop();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void destroy() throws Exception {
        if (available) {
            stop();
            listenerContainer.destroy();
            connectionFactory.destroy();
        }
    }
}
