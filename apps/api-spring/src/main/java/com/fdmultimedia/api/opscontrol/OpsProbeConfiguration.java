package com.fdmultimedia.api.opscontrol;

import com.fdmultimedia.api.assets.ObjectStorageService;
import com.fdmultimedia.api.opscontrol.DependencyHealthService.Probe;
import com.fdmultimedia.api.opscontrol.DependencyHealthService.ProbeResult;
import com.fdmultimedia.api.opscontrol.OpsModels.ComponentStatus;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The dependencies the API itself relies on. Each probe is a cheap, credential-free-to-expose check that blocks at most a moment;
 * {@link DependencyHealthService} additionally bounds, caches and parallelises them. Ollama is deliberately absent: it is reached only by
 * the Worker, so the API has no truthful way to observe it and reports no state for it rather than a guess.
 */
@Configuration
class OpsProbeConfiguration {

    @Bean
    Probe postgresProbe(DataSource dataSource) {
        return new Probe() {
            public String name() { return "POSTGRES"; }
            public boolean configured() { return true; }
            public ProbeResult probe() {
                try (Connection connection = dataSource.getConnection()) {
                    return connection.isValid(1) ? new ProbeResult(ComponentStatus.HEALTHY, "UP") : new ProbeResult(ComponentStatus.UNAVAILABLE, "INVALID_CONNECTION");
                } catch (Exception ex) {
                    return new ProbeResult(ComponentStatus.UNAVAILABLE, "CONNECTION_FAILED");
                }
            }
        };
    }

    @Bean
    Probe minioProbe(ObjectStorageService storage) {
        return new Probe() {
            public String name() { return "MINIO"; }
            public boolean configured() { return true; }
            public ProbeResult probe() {
                return storage.isAvailable() ? new ProbeResult(ComponentStatus.HEALTHY, "UP") : new ProbeResult(ComponentStatus.UNAVAILABLE, "UNREACHABLE");
            }
        };
    }

    @Bean
    Probe rabbitMqProbe(Environment environment) {
        return new Probe() {
            public String name() { return "RABBITMQ"; }
            public boolean configured() { return environment.getProperty("spring.rabbitmq.host") != null; }
            public ProbeResult probe() {
                String host = environment.getProperty("spring.rabbitmq.host");
                int port = environment.getProperty("spring.rabbitmq.port", Integer.class, 5672);
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), 500);
                    return new ProbeResult(ComponentStatus.HEALTHY, "UP");
                } catch (Exception ex) {
                    return new ProbeResult(ComponentStatus.UNAVAILABLE, "UNREACHABLE");
                }
            }
        };
    }
}
