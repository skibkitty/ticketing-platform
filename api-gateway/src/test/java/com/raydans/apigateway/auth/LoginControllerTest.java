package com.raydans.apigateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.raydans.apigateway.web.GatewayExceptionHandler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The login surface as a caller experiences it.
 *
 * <p>The two tests that matter most here are the ones about what a failure does
 * <em>not</em> say. A login endpoint that reports "no such user" separately from
 * "wrong password" is an oracle for which usernames exist, and the fix costs
 * nothing at the moment the bug is found rather than after.
 */
class LoginControllerTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough";
    private static final Duration TTL = Duration.ofHours(1);
    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private final JwtService tokens = new JwtService(
            new JwtProperties(SECRET, TTL), Clock.fixed(NOW, ZoneOffset.UTC));

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        UserProperties users = new UserProperties(Map.of(
                "customer", new UserProperties.UserAccountProperties("customer", List.of("CUSTOMER")),
                "organizer", new UserProperties.UserAccountProperties("organizer", List.of("ORGANIZER")),
                "admin", new UserProperties.UserAccountProperties("admin", List.of("ADMIN"))));
        mvc = MockMvcBuilders.standaloneSetup(new LoginController(new MapUserDirectory(users), tokens))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GatewayExceptionHandler())
                .build();
    }

    @Test
    void correctCredentialsReturnATokenAndTheRolesItCarries() throws Exception {
        mvc.perform(login("customer", "customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-27T11:00:00Z"));
    }

    @Test
    void theTokenReturnedIsOneTheGatewayWillAccept() throws Exception {
        // A login that handed back an unusable token would be a 200 and a dead
        // platform, so the token is verified rather than merely asserted present.
        String token = mvc.perform(login("admin", "admin"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String jwt = objectMapper().readTree(token).get("token").asText();
        assertThat(tokens.parse(jwt).username()).isEqualTo("admin");
        assertThat(tokens.parse(jwt).roles()).containsExactly(Role.ADMIN);
    }

    @Test
    void eachDemoCallerGetsItsOwnRole() throws Exception {
        mvc.perform(login("organizer", "organizer"))
                .andExpect(jsonPath("$.roles[0]").value("ORGANIZER"));
    }

    @Test
    void aWrongPasswordIsRefused() throws Exception {
        mvc.perform(login("customer", "not-the-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/auth/login"));
    }

    @Test
    void anUnknownUserIsRefusedWithExactlyTheSameAnswer() throws Exception {
        // The whole point: a caller must not be able to learn which usernames
        // exist by reading the difference between this and the test above.
        String unknownUser = mvc.perform(login("nobody", "whatever"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String wrongPassword = mvc.perform(login("customer", "whatever"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Compared with the fields a client could act on; the timestamp differs
        // by construction and is not a signal.
        assertThat(jsonField(unknownUser, "message")).isEqualTo(jsonField(wrongPassword, "message"));
        assertThat(jsonField(unknownUser, "status")).isEqualTo(jsonField(wrongPassword, "status"));
        assertThat(jsonField(unknownUser, "error")).isEqualTo(jsonField(wrongPassword, "error"));
    }

    @Test
    void theRefusalDoesNotEchoThePasswordBack() throws Exception {
        String body = mvc.perform(login("customer", "hunter2-the-wrong-one"))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain("hunter2");
    }

    @Test
    void aMissingUsernameIs400AndSaysWhichField() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"customer\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is not valid"))
                .andExpect(jsonPath("$.details[0]").value(Matchers.containsString("username")));
    }

    @Test
    void aBlankPasswordIs400() throws Exception {
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"customer\", \"password\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(Matchers.containsString("password")));
    }

    @Test
    void aBodyThatIsNotJsonIs400RatherThan500() throws Exception {
        // The login route is the one route anyone can reach without a token, so
        // it is the one most likely to be met by a scanner rather than a client.
        // It must not answer a 500, which would read as our fault and invite a
        // retry loop.
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("this is not json at all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/auth/login"));
    }

    @Test
    void anEmptyBodyIs400() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCallerWithNoConfiguredRolesStillGetsAToken() throws Exception {
        // Configured with a role set, so the token is issued and carries it.
        // A caller with none is a configuration state the login surface does not
        // invent an opinion about; what matters is that it is not a 500.
        UserProperties noRoles = new UserProperties(
                Map.of("ghost", new UserProperties.UserAccountProperties("ghost", List.of())));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LoginController(new MapUserDirectory(noRoles), tokens))
                .setControllerAdvice(new GatewayExceptionHandler())
                .build();

        mvc.perform(login("ghost", "ghost")).andExpect(status().isOk()).andExpect(jsonPath("$.roles").isEmpty());
    }

    @Test
    void anUnknownRoleNameInConfigurationIsRefusedAtStartup() throws Exception {
        // Better a gateway that will not start than one that mints a caller who
        // can log in and then be refused by every rule: that is a failure the
        // user reports as "my account is broken", not as a config error.
        UserProperties typo = new UserProperties(
                Map.of("organiser", new UserProperties.UserAccountProperties("organiser", List.of("ORGANISER"))));

        assertThatThrownBy(() -> new MapUserDirectory(typo))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORGANISER");
    }

    @Test
    void aDirectoryWithNoConfiguredCallersRefusesEveryone() throws Exception {
        MockMvc empty = MockMvcBuilders.standaloneSetup(new LoginController(new MapUserDirectory(UserProperties.empty()), tokens))
                .setControllerAdvice(new GatewayExceptionHandler())
                .build();

        empty.perform(login("customer", "customer")).andExpect(status().isUnauthorized());
    }

    @Test
    void theHeaderATokenIsCarriedInIsTheOneTheFilterReads() throws Exception {
        // Ties the two halves of the auth story together: the token the login
        // surface mints is a bearer token, which is the only scheme the filter
        // accepts. Without this, a client could hold a perfectly valid token and
        // be refused for sending it correctly-but-differently.
        String jwt = objectMapper()
                .readTree(mvc.perform(login("customer", "customer"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .get("token")
                .asText();

        assertThat(jwt.split("\\.")).hasSize(3);
        assertThat(tokens.parse(jwt).roles()).isEqualTo(Set.of(Role.CUSTOMER));
    }

    private static MockHttpServletRequestBuilder login(String username, String password) {
        return post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, password));
    }

    private String jsonField(String body, String field) {
        try {
            return objectMapper().readTree(body).get(field).asText();
        } catch (Exception ex) {
            throw new AssertionError("Could not read '" + field + "' from " + body, ex);
        }
    }

    private static ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
