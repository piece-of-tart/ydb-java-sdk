package tech.ydb.test.integration.docker;

import com.google.common.annotations.VisibleForTesting;
import org.testcontainers.utility.TestcontainersConfiguration;

import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.core.grpc.GrpcTransportBuilder;
import tech.ydb.test.integration.YdbEnvironment;
import tech.ydb.test.integration.YdbHelper;
import tech.ydb.test.integration.YdbHelperFactory;
import tech.ydb.test.integration.utils.PortsGenerator;

/**
 *
 * @author Aleksandr Gorshenin
 */
public class DockerHelperFactory extends YdbHelperFactory {
    private final YdbEnvironment env;
    private final YdbDockerContainer container;

    public DockerHelperFactory(YdbEnvironment env) {
        this(env, new YdbDockerContainer(env, new PortsGenerator()));
    }

    @VisibleForTesting
    DockerHelperFactory(YdbEnvironment env, YdbDockerContainer container) {
        this.env = env;
        this.container = container;
    }

    @Override
    public YdbHelper createHelper() {
        container.start();

        return new YdbHelper() {
            @Override
            public GrpcTransport createTransport() {
                GrpcTransportBuilder builder = GrpcTransport.forEndpoint(endpoint(), container.database());
                if (env.ydbUseTls()) {
                    builder.withSecureConnection(container.pemCert());
                }
                return builder.build();
            }

            @Override
            public String endpoint() {
                return env.ydbUseTls() ? container.secureEndpoint() : container.nonSecureEndpoint();
            }

            @Override
            public String database() {
                return container.database();
            }

            @Override
            public boolean useTls() {
                return env.ydbUseTls();
            }

            @Override
            public String authToken() {
                // connection to docker container always is anonymous
                return null;
            }

            @Override
            public byte[] pemCert() {
                return container.pemCert();
            }

            @Override
            public void close() {
                if (env.dockerReuse() && TestcontainersConfiguration.getInstance().environmentSupportsReuse()) {
                    return;
                }

                container.stop();
                container.close();
            }
        };
    }
}
