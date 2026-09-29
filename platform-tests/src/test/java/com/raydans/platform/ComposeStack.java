package com.raydans.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.yaml.snakeyaml.Yaml;

/**
 * The compose files as configuration, and Compose's own answer about them.
 *
 * <p>Two ways of asking the same question, deliberately. {@link #fromFiles} reads
 * what is committed, which always works and is what CI can rely on.
 * {@link #fromComposeCli} asks the {@code docker compose} binary to resolve it,
 * which is the answer that counts because it is the model the containers
 * actually start from — it applies defaults, merges overrides and expands
 * {@code ${...}}. That one needs the binary, so the tests that use it declare
 * the assumption rather than failing on a machine without Docker.
 *
 * <p>Neither is a grep. A port is parsed into the interface, the published port
 * and the container port, because the question that matters is not "does the
 * word 8084 appear" but "is anything published, and on which interfaces" — and
 * {@code 127.0.0.1:8084:8080} is still a published port.
 */
final class ComposeStack {

    /** The only HTTP port the platform publishes, and the only application surface. */
    static final String GATEWAY = "api-gateway";

    /**
     * The services the gateway fronts, which hold no authentication of their own
     * and therefore have nothing of their own to stop a request that skipped the
     * gateway (ADR 002, ADR 011).
     */
    static final List<String> INTERNAL_SERVICES =
            List.of("reservation-service", "payment-service", "notification-service");

    /**
     * Values for the four variables the gateway's compose entry refuses to
     * interpolate without. Not secrets and not credentials: the test never
     * starts a container, it only needs {@code compose config} to get past
     * interpolation, and these are obviously not the values a real run uses.
     */
    private static final Map<String, String> INTERPOLATION_STUBS = Map.of(
            "JWT_SECRET", "not-a-real-key-this-is-only-enough-bytes-to-satisfy-interpolation-0123456789",
            "DEMO_CUSTOMER_PASSWORD", "not-a-real-password",
            "DEMO_ORGANIZER_PASSWORD", "not-a-real-password",
            "DEMO_ADMIN_PASSWORD", "not-a-real-password");

    private final Map<String, Map<String, Object>> services;

    private ComposeStack(Map<String, Map<String, Object>> services) {
        this.services = services;
    }

    /**
     * The root of the repository: the directory holding the aggregator pom, which
     * is the only pom here that lists modules and the only one not itself a
     * module. Located by walking up from the working directory rather than
     * hard-coded, so the test does not care which module it is run from.
     */
    static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.isRegularFile(pom) && readString(pom).contains("<modules>")) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not find the repository root (the directory holding the aggregator pom.xml) from "
                        + Path.of("").toAbsolutePath());
    }

    static ComposeStack fromFiles(String... fileNames) {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        for (String fileName : fileNames) {
            Object parsed = new Yaml().load(readString(repositoryRoot().resolve(fileName)));
            if (!(parsed instanceof Map<?, ?> document) || !(document.get("services") instanceof Map<?, ?> entries)) {
                throw new IllegalStateException(fileName + " has no 'services' mapping; cannot read it as a stack");
            }
            entries.forEach((name, body) -> {
                if (!(body instanceof Map<?, ?> service)) {
                    throw new IllegalStateException(fileName + ": service '" + name + "' is not a mapping");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> asMap = (Map<String, Object>) service;
                // Map.merge hands the remapping function the existing value first
                // and the incoming one second.
                all.merge(String.valueOf(name), asMap, (existing, incoming) -> {
                    // Compose's own rule for a service declared in two files: the
                    // later file's keys win. Enough for what the tests read, and
                    // a mismatch would be a question about the override worth
                    // asking rather than papering over.
                    Map<String, Object> merged = new LinkedHashMap<>(existing);
                    merged.putAll(incoming);
                    return merged;
                });
            });
        }
        return new ComposeStack(all);
    }

    /** Whether the compose binary is present and can report a version. */
    static boolean composeCliAvailable() {
        try {
            return run(List.of("docker", "compose", "version"), Path.of(System.getProperty("java.io.tmpdir")))
                    .exitCode() == 0;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * The model Compose itself resolves, from {@code -f} in the given order.
     *
     * <p>This is the one that knows the answer for real: it is the same command
     * {@code docker compose up} runs its own configuration through.
     */
    static ComposeStack fromComposeCli(String... fileNames) {
        List<String> command = new ArrayList<>(List.of("docker", "compose"));
        for (String fileName : fileNames) {
            command.addAll(List.of("-f", fileName));
        }
        command.addAll(List.of("config", "--format", "json"));

        ProcessResult result;
        try {
            result = run(command, repositoryRoot(), INTERPOLATION_STUBS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running " + String.join(" ", command), ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not run " + String.join(" ", command), ex);
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "`" + String.join(" ", command) + "` failed with " + result.exitCode() + ":\n" + result.output());
        }
        return fromResolvedJson(result.output());
    }

    /** The `services` section of {@code docker compose config --format json}. */
    private static ComposeStack fromResolvedJson(String json) {
        try {
            JsonNode services = new ObjectMapper().readTree(json).path("services");
            if (!services.isObject()) {
                throw new IllegalStateException("`compose config` reported no services");
            }
            Map<String, Map<String, Object>> all = new LinkedHashMap<>();
            services.fields().forEachRemaining(entry -> {
                Map<String, Object> service = new LinkedHashMap<>();
                entry.getValue().fields()
                        .forEachRemaining(field -> service.put(field.getKey(), toPlainJava(field.getValue())));
                all.put(entry.getKey(), service);
            });
            return new ComposeStack(all);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the output of `compose config`", ex);
        }
    }

    private static Object toPlainJava(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> asMap = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> asMap.put(field.getKey(), toPlainJava(field.getValue())));
            return asMap;
        }
        if (node.isArray()) {
            List<Object> asList = new ArrayList<>();
            node.forEach(element -> asList.add(toPlainJava(element)));
            return asList;
        }
        return node.asText();
    }

    List<String> serviceNames() {
        return List.copyOf(services.keySet());
    }

    boolean hasService(String name) {
        return services.containsKey(name);
    }

    /**
     * Every host port this stack publishes for the named service.
     *
     * <p>Empty means nothing outside the compose network can open a socket to
     * the container at all, which is the property ADR 012 turns on.
     */
    List<PublishedPort> publishedPorts(String serviceName) {
        Object declared = require(serviceName).get("ports");
        if (declared == null) {
            return List.of();
        }
        if (!(declared instanceof List<?> entries)) {
            throw new IllegalStateException(serviceName + ": 'ports' is not a list");
        }
        List<PublishedPort> ports = new ArrayList<>();
        for (Object entry : entries) {
            ports.add(entry instanceof String shortForm ? parseShortForm(serviceName, shortForm) : parseLongForm(serviceName, entry));
        }
        return ports;
    }

    /**
     * {@code host:published:target}, with any of the three absent. A host of
     * {@code null} is every interface, which is the case a short-form binding
     * means and the one worth being explicit about.
     */
    private static PublishedPort parseShortForm(String serviceName, String binding) {
        String[] parts = binding.split(":");
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalStateException(serviceName + ": could not read '" + binding + "' as a port binding");
        }
        String hostIp = parts.length == 3 ? parts[0] : null;
        String published = parts[parts.length - 2];
        String target = parts[parts.length - 1];
        if (!isNumeric(published) || !isNumeric(target)) {
            throw new IllegalStateException(serviceName + ": could not read '" + binding + "' as a port binding");
        }
        return new PublishedPort(serviceName, hostIp, Integer.parseInt(published), Integer.parseInt(target));
    }

    @SuppressWarnings("unchecked")
    private static PublishedPort parseLongForm(String serviceName, Object entry) {
        if (!(entry instanceof Map<?, ?> longForm)) {
            throw new IllegalStateException(serviceName + ": could not read '" + entry + "' as a port binding");
        }
        Map<String, Object> binding = (Map<String, Object>) longForm;
        return new PublishedPort(
                serviceName,
                (String) binding.get("host_ip"),
                Integer.parseInt(String.valueOf(binding.get("published"))),
                Integer.parseInt(String.valueOf(binding.get("target"))));
    }

    private static boolean isNumeric(String value) {
        return value.chars().allMatch(Character::isDigit) && !value.isEmpty();
    }

    /** The service's environment, however the entry spells it. */
    Map<String, String> environment(String serviceName) {
        Object declared = require(serviceName).get("environment");
        if (declared == null) {
            return Map.of();
        }
        if (!(declared instanceof Map<?, ?> entries)) {
            throw new IllegalStateException(serviceName + ": 'environment' is not a mapping");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        entries.forEach((key, value) -> environment.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
        return environment;
    }

    private Map<String, Object> require(String serviceName) {
        Map<String, Object> service = services.get(serviceName);
        if (service == null) {
            throw new IllegalStateException(
                    "The stack declares no service called '" + serviceName + "'. It has: " + serviceNames());
        }
        return service;
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + path, ex);
        }
    }

    private static ProcessResult run(List<String> command, Path workingDirectory) throws IOException, InterruptedException {
        return run(command, workingDirectory, Map.of());
    }

    private static ProcessResult run(List<String> command, Path workingDirectory, Map<String, String> environment)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("`" + String.join(" ", command) + "` did not finish in 60s");
        }
        return new ProcessResult(process.exitValue(), output);
    }

    /**
     * A port the host can reach a container on.
     *
     * @param hostIp the interface it is bound to, or {@code null} for every one
     *     of them — the short form {@code "8080:8080"} means this, and it is
     *     the difference between "the LAN can reach it" and "only this machine
     *     can"
     */
    record PublishedPort(String service, String hostIp, int published, int target) {

        boolean isOnEveryInterface() {
            return hostIp == null || hostIp.isBlank() || "0.0.0.0".equals(hostIp);
        }

        boolean isOnLoopbackOnly() {
            return "127.0.0.1".equals(hostIp) || "::1".equals(hostIp);
        }
    }

    private record ProcessResult(int exitCode, String output) {}
}
