package com.guoyongzheng.training.web.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.AbstractRedisClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

/**
 * Custom {@link LettuceConnectionFactory} that suppresses Lettuce's automatic
 * {@code CLIENT SETINFO} handshake.
 *
 * <p><b>Why this is needed.</b> When Lettuce builds a {@link RedisURI} it always stamps client
 * metadata onto it — the {@code RedisURI.Builder} constructor seeds {@code libraryName="Lettuce"}
 * and {@code libraryVersion=<version>}. After connecting, Lettuce's {@code RedisHandshake} reads
 * that metadata and, when it is non-null, issues {@code CLIENT SETINFO LIBNAME}/{@code LIBVER}.
 * Spring Data Redis' own {@code createRedisURIAndApplySettings} never clears these fields, so the
 * default auto-configured factory always sends {@code CLIENT SETINFO}.</p>
 *
 * <p>A Redis ACL user that is granted only least-privilege commands (read/write + ping) but not
 * the {@code client} command therefore fails the handshake with
 * {@code NOPERM ... client|setinfo} and the connection is aborted — even though every command the
 * application actually issues would be allowed.</p>
 *
 * <p><b>How this is fixed.</b> The handshake only emits {@code CLIENT SETINFO} when the library
 * name/version are non-null. {@code LettuceClientConfiguration} exposes no toggle for this (it
 * only covers ssl / clientResources / clientOptions / readFrom / credentials / clientName /
 * timeouts), so the only lever is the {@link RedisURI} itself. We therefore build the
 * {@link RedisURI} ourselves and clear {@code libraryName}/{@code libraryVersion} with
 * {@link RedisURI#setLibraryName(String)} / {@link RedisURI#setLibraryVersion(String)} — these
 * setters are null-safe (unlike {@code withLibraryName(...)}, which rejects null). With the
 * metadata cleared, no {@code CLIENT SETINFO} is sent and a least-privilege ACL user can connect.</p>
 *
 * <p>This class overrides {@link LettuceConnectionFactory#createClient()} to substitute our
 * metadata-free URI. Everything else (host, port, database, credentials, client options, command
 * timeout, connection pooling/sharing) is inherited from Spring Data Redis, and
 * {@code super(standaloneConfiguration, clientConfiguration)} is used so the standard
 * auto-configured behaviour is preserved. Host/port/username/password come from
 * {@code spring.data.redis.*} (driven by {@code TRAINING_REDIS_*}).</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "training", name = "redis-host")
public class RedisConnectionConfig {

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties properties) {
        LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2))
                .build();

        // Sentinel mode: configured via spring.data.redis.sentinel.{master,nodes}.
        // Uses the framework's default client (topology discovery), because our
        // metadata-free override below only applies to standalone connections.
        RedisProperties.Sentinel sentinel = properties.getSentinel();
        boolean useSentinel = sentinel != null
                && sentinel.getMaster() != null
                && !sentinel.getMaster().isBlank()
                && !sentinel.getNodes().isEmpty();
        if (useSentinel) {
            return new LettuceConnectionFactory(
                    buildSentinelConfiguration(properties, sentinel), clientConfiguration);
        }

        RedisStandaloneConfiguration standaloneConfiguration = buildStandaloneConfiguration(properties);

        return new LettuceConnectionFactory(standaloneConfiguration, clientConfiguration) {
            @Override
            protected AbstractRedisClient createClient() {
                // Build our own metadata-free URI so the handshake skips CLIENT SETINFO.
                RedisClient client = RedisClient.create(buildMetadataFreeRedisUri(standaloneConfiguration));
                ClientOptions clientOptions = clientConfiguration.getClientOptions().orElse(null);
                if (clientOptions != null) {
                    client.setOptions(clientOptions);
                }
                return client;
            }
        };
    }

    /**
     * Sentinel topology configuration from {@code spring.data.redis.sentinel.*}, carrying the
     * ACL username/password onto both the sentinel connection and the discovered master.
     */
    private static RedisSentinelConfiguration buildSentinelConfiguration(RedisProperties properties,
                                                                         RedisProperties.Sentinel sentinel) {
        RedisSentinelConfiguration configuration = new RedisSentinelConfiguration()
                .master(sentinel.getMaster());
        for (String node : sentinel.getNodes()) {
            String[] hostPort = node.trim().split(":");
            configuration.addSentinel(new RedisNode(hostPort[0].trim(), Integer.parseInt(hostPort[1].trim())));
        }
        configuration.setDatabase(properties.getDatabase());
        String username = properties.getUsername();
        if (username != null && !username.isBlank()) {
            configuration.setUsername(username);
        }
        configuration.setPassword(RedisPassword.of(properties.getPassword()));
        return configuration;
    }

    /**
     * Maps {@code spring.data.redis.*} onto a {@link RedisStandaloneConfiguration}, carrying the
     * ACL username when present (required for Redis 6+ ACL; without it the connection would fall
     * back to the {@code default} user, which is disabled on the target instance).
     */
    private static RedisStandaloneConfiguration buildStandaloneConfiguration(RedisProperties properties) {
        RedisStandaloneConfiguration configuration =
                new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        configuration.setDatabase(properties.getDatabase());

        String username = properties.getUsername();
        if (username != null && !username.isBlank()) {
            configuration.setUsername(username);
        }

        // RedisPassword.of(...) is null-safe (returns NONE for null/blank).
        configuration.setPassword(RedisPassword.of(properties.getPassword()));
        return configuration;
    }

    /**
     * Builds the connection {@link RedisURI} with {@code libraryName}/{@code libraryVersion} cleared
     * so Lettuce does not issue {@code CLIENT SETINFO} on connect.
     */
    private static RedisURI buildMetadataFreeRedisUri(RedisStandaloneConfiguration configuration) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(configuration.getHostName())
                .withPort(configuration.getPort())
                .withDatabase(configuration.getDatabase());

        String username = configuration.getUsername();
        RedisPassword password = configuration.getPassword();
        if (username != null && !username.isBlank()) {
            if (password.isPresent()) {
                builder.withAuthentication(username, password.get());
            }
            // Username without a password: set it directly on the URI after build().
        } else if (password.isPresent()) {
            builder.withPassword(password.get());
        }

        RedisURI uri = builder.build();
        if (username != null && !username.isBlank() && !password.isPresent()) {
            uri.setUsername(username);
        }
        // Nulling the metadata makes RedisHandshake skip CLIENT SETINFO (null-safe setters).
        uri.setLibraryName(null);
        uri.setLibraryVersion(null);
        return uri;
    }
}