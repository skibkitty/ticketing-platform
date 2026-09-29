package com.raydans.platform;

import static com.raydans.platform.ComposeStack.GATEWAY;
import static com.raydans.platform.ComposeStack.INTERNAL_SERVICES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The same question asked of Compose rather than of the file: what does
 * {@code docker compose config} say the containers will actually be started
 * with?
 *
 * <p>Worth having alongside {@link InternalServiceExposureTests} because that one
 * reads the committed YAML, and a YAML file is not what runs. Compose applies
 * defaults, merges overrides, expands {@code ${...}} and normalises both the
 * short and long port forms, and it is the result of all of that which decides
 * whether a port is reachable. A file-only test would miss anything the
 * resolution adds.
 *
 * <p>Needs the {@code docker compose} binary, so it declares that as an
 * assumption instead of failing where Docker is not installed — the file-level
 * checks above still run there, and say the same thing about the committed
 * configuration.
 */
class ComposeResolutionTests {

    private static final String DEFAULT_STACK = "docker-compose.yml";
    private static final String DEBUG_OVERRIDE = "docker-compose.debug.yml";

    @BeforeAll
    static void composeMustBeInstalled() {
        assumeTrue(
                ComposeStack.composeCliAvailable(),
                "docker compose is not installed here; the committed configuration is still checked by"
                        + " InternalServiceExposureTests");
    }

    @Test
    @DisplayName("the default stack resolves, and no internal service comes back with a published port")
    void theDefaultStackPublishesNoInternalPort() {
        ComposeStack resolved = ComposeStack.fromComposeCli(DEFAULT_STACK);

        for (String service : INTERNAL_SERVICES) {
            assertThat(resolved.publishedPorts(service))
                    .as("%s, as Compose resolves it", service)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the default stack resolves to the same published ports the file declares")
    void theDefaultStackStillPublishesTheGateway() {
        ComposeStack resolved = ComposeStack.fromComposeCli(DEFAULT_STACK);

        assertThat(resolved.publishedPorts(GATEWAY))
                .singleElement()
                .satisfies(port -> {
                    assertThat(port.published()).isEqualTo(8080);
                    assertThat(port.isOnEveryInterface()).isTrue();
                });
    }

    @Test
    @DisplayName("adding the debug override resolves to loopback-only internal ports")
    void theDebugOverrideResolvesToLoopbackOnly() {
        ComposeStack resolved = ComposeStack.fromComposeCli(DEFAULT_STACK, DEBUG_OVERRIDE);

        for (String service : INTERNAL_SERVICES) {
            List<ComposeStack.PublishedPort> ports = resolved.publishedPorts(service);
            assertThat(ports)
                    .as("%s, as Compose resolves the override", service)
                    .singleElement()
                    .satisfies(port -> {
                        assertThat(port.isOnLoopbackOnly()).isTrue();
                        assertThat(port.isOnEveryInterface()).isFalse();
                    });
        }
    }
}
