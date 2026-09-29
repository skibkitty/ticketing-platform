package com.raydans.platform;

import static com.raydans.platform.ComposeStack.GATEWAY;
import static com.raydans.platform.ComposeStack.INTERNAL_SERVICES;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The published HTTP surface of the default stack, which is the whole of the
 * authentication and authorization boundary (ADR 002, ADR 012).
 *
 * <p>The gateway checks a signature and a role. The services behind it do not,
 * by decision: they trust the identity headers the gateway sets and hold no
 * security dependency of their own. That is a good design and it is only worth
 * anything if the services behind it cannot be reached any other way, because
 * a caller who skips the gateway is sending {@code X-Customer-Id} itself.
 *
 * <p>So these are not assertions about a comment. The thing that makes the
 * gateway the trust boundary is a property of the resolved configuration — what
 * the host can open a socket to — and a comment in a compose file is not that
 * property. {@code 127.0.0.1:8084:8080} reads as "internal" and is not: it hands
 * anyone with a process on the machine a way to read any Customer's
 * Notifications, because {@code GET /api/v1/admin/customers/42/notifications}
 * is guarded by ADMIN at the gateway and by nothing at all in the service
 * (ADR 011).
 */
class InternalServiceExposureTests {

    private static final String DEFAULT_STACK = "docker-compose.yml";
    private static final String DEBUG_OVERRIDE = "docker-compose.debug.yml";

    /**
     * Every port the default stack publishes, by service. An allowlist rather
     * than a rule about "the internal services", because the interesting failure
     * is the next service somebody adds: a rule naming the three services today
     * would be satisfied by a fourth publishing its port tomorrow, and this list
     * makes that a deliberate edit.
     */
    private static final Map<String, List<Integer>> PUBLISHED_ON_THE_HOST =
            Map.of(GATEWAY, List.of(8080), "postgres", List.of(5432), "kafka", List.of(29092), "kafka-ui", List.of(8090));

    private final ComposeStack stack = ComposeStack.fromFiles(DEFAULT_STACK);

    @Test
    @DisplayName("no internal service publishes a port, on any interface")
    void noInternalServiceIsPublishedToTheHost() {
        for (String service : INTERNAL_SERVICES) {
            assertThat(stack.publishedPorts(service))
                    .as("%s is internal: it must be reachable by service name on the compose network and from no host"
                            + " interface at all, because it has no authentication of its own to fall back on", service)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("the published host ports are the gateway and the infrastructure, and nothing else")
    void theOnlyPublishedPortsAreTheOnesThatWereDecidedOn() {
        Set<String> actuallyPublished = stack.serviceNames().stream()
                .filter(service -> !stack.publishedPorts(service).isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(actuallyPublished)
                .as("a new published port is a new way around the gateway, so adding one means deciding it here")
                .isEqualTo(PUBLISHED_ON_THE_HOST.keySet());
    }

    @Test
    @DisplayName("the gateway is published on every interface, because it is the public surface")
    void theGatewayIsThePublicSurface() {
        List<ComposeStack.PublishedPort> gatewayPorts = stack.publishedPorts(GATEWAY);

        assertThat(gatewayPorts).singleElement().satisfies(port -> {
            assertThat(port.published()).isEqualTo(8080);
            assertThat(port.target()).isEqualTo(8080);
            assertThat(port.isOnEveryInterface())
                    .as("the gateway is the entry point, so it has to be reachable from off this machine")
                    .isTrue();
        });
    }

    @Test
    @DisplayName("the gateway reaches each internal service by name, over the compose network")
    void theGatewayReachesItsServicesOverTheComposeNetwork() {
        // Spelled out rather than derived from the service name, so that
        // renaming a service and forgetting the matching variable is a
        // compilation error here instead of a gateway that starts with a
        // missing route and a stack that answers connection-refused.
        Map<String, String> routeOf = Map.of(
                "RESERVATION_SERVICE_URL", "http://reservation-service:8080",
                "PAYMENT_SERVICE_URL", "http://payment-service:8080",
                "NOTIFICATION_SERVICE_URL", "http://notification-service:8080");
        Map<String, String> environment = stack.environment(GATEWAY);

        assertThat(environment).containsAllEntriesOf(routeOf);
    }

    @Test
    @DisplayName("no service is told to reach another over the host, which would mean a host-published port is relied on")
    void noServiceRouteDependsOnAHostPort() {
        // A URL of http://localhost:8084 in the gateway's environment can only
        // work if something published 8084 to the host, so this is the same
        // finding as a published port, reached from the other side: the gateway
        // would be talking to the host's copy of a service, and the hop the
        // trust boundary depends on would be one the compose file cannot see.
        //
        // Scoped to the service URLs, because localhost is also a legitimate
        // answer to a different question: CORS_ALLOWED_ORIGINS names the
        // browser a client runs in, not a route to anything.
        stack.serviceNames().forEach(service -> stack.environment(service).entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("_SERVICE_URL"))
                .forEach(route -> assertThat(route.getValue())
                        .as("%s routes to %s; on the compose network that must name a service", service, route.getKey())
                        .doesNotContain("localhost")
                        .doesNotContain("127.0.0.1")));
    }

    @Test
    @DisplayName("the debug override publishes the internal ports, and only on loopback")
    void theDebugOverrideStaysOnLoopback() {
        // The escape hatch is a separate file, so the normal path cannot take
        // it. What it may not do is bind wide: 0.0.0.0:8084:8080 would be the
        // bypass with a step added, and it would still pass every other test
        // here.
        ComposeStack debug = ComposeStack.fromFiles(DEBUG_OVERRIDE);

        for (String service : INTERNAL_SERVICES) {
            assertThat(debug.publishedPorts(service)).singleElement().satisfies(port -> {
                assertThat(port.isOnLoopbackOnly())
                        .as("%s is published for debugging only, and a debugging convenience is not a control", service)
                        .isTrue();
                assertThat(port.isOnEveryInterface()).isFalse();
            });
        }
    }

    @Test
    @DisplayName("the debug override adds ports without taking any away")
    void theDebugOverrideChangesNothingElse() {
        ComposeStack debug = ComposeStack.fromFiles(DEFAULT_STACK, DEBUG_OVERRIDE);

        assertThat(debug.serviceNames()).isEqualTo(stack.serviceNames());
        assertThat(debug.publishedPorts(GATEWAY)).isEqualTo(stack.publishedPorts(GATEWAY));
        INTERNAL_SERVICES.forEach(service ->
                assertThat(debug.environment(service)).isEqualTo(stack.environment(service)));
    }
}
