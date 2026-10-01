package com.raydans.notificationservice.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.raydans.notificationservice.notification.NotificationPage;
import com.raydans.notificationservice.notification.NotificationResponse;
import com.raydans.notificationservice.notification.NotificationService;
import com.raydans.notificationservice.notification.NotificationType;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationControllerTest {

    private static final int DEFAULT_PAGE_SIZE = 50;

    // Not stubOnly: the paging tests have to verify what the controller passed on, which
    // is the whole claim being made — that a limit and cursor reach the service rather
    // than being applied or dropped in the transport layer.
    NotificationService notifications = mock(NotificationService.class);

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        // The same ObjectMapper shape Spring Boot builds, so the asserted wire format
        // (ISO-8601 instants) is the one a real client actually receives.
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mvc = MockMvcBuilders
                .standaloneSetup(new NotificationController(notifications, DEFAULT_PAGE_SIZE))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listForCustomerReturnsTheirNotificationsNewestFirst() throws Exception {
        when(notifications.listForCustomer(99L, DEFAULT_PAGE_SIZE, null))
                .thenReturn(page(null, expired(), confirmed()));

        mvc.perform(get("/api/v1/notifications").header(NotificationController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reservationId").value(202))
                .andExpect(jsonPath("$.items[0].recipientCustomerId").value(99))
                .andExpect(jsonPath("$.items[0].type").value("RESERVATION_EXPIRED"))
                .andExpect(jsonPath("$.items[0].message").value("Your reservation 202 expired before payment completed."))
                .andExpect(jsonPath("$.items[0].sentAt").value("2026-09-25T10:15:30Z"))
                .andExpect(jsonPath("$.items[1].reservationId").value(201))
                .andExpect(jsonPath("$.items[1].type").value("RESERVATION_CONFIRMED"));
    }

    // The page carries the position of the next one, because a keyset cursor a client is
    // never handed is a cursor no client can page from: page two is only reachable if the
    // response says where it starts. These three pin both ends of that — a page with more
    // behind it names the position, a page that is the last one does not.

    @Test
    void aPageWithAnotherPageBehindItCarriesTheCursorForIt() throws Exception {
        // Deliberately not something the controller could have built: the response must
        // carry the value the service issued, not one re-derived from the rows on the page.
        when(notifications.listForCustomer(99L, 2, null))
                .thenReturn(page("next-page-position-from-the-service", expired(), confirmed()));

        mvc.perform(get("/api/v1/notifications")
                        .header(NotificationController.CUSTOMER_HEADER, "99")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value("next-page-position-from-the-service"));
    }

    @Test
    void theLastPageCarriesNoCursor() throws Exception {
        when(notifications.listForCustomer(99L, 2, "Y3Vyc29y")).thenReturn(page(null, confirmed()));

        mvc.perform(get("/api/v1/notifications")
                        .header(NotificationController.CUSTOMER_HEADER, "99")
                        .param("limit", "2")
                        .param("cursor", "Y3Vyc29y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void aPageWithNoNotificationsIsEmptyAndOffersNoCursor() throws Exception {
        when(notifications.listForCustomer(404L, DEFAULT_PAGE_SIZE, null)).thenReturn(page(null));

        mvc.perform(get("/api/v1/notifications").header(NotificationController.CUSTOMER_HEADER, "404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void aRequestWithNoCustomerHeaderIs400() throws Exception {
        // The whole point of binding to the header: with nothing proven about who is
        // asking there is no inbox to read, so this cannot fall back to a parameter
        // or a default. Also the reason the route is closed to anything that did not
        // come through the gateway (ADR 011).
        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString(NotificationController.CUSTOMER_HEADER)))
                .andExpect(jsonPath("$.path").value("/api/v1/notifications"));
    }

    @Test
    void aNonNumericCustomerHeaderIs400() throws Exception {
        mvc.perform(get("/api/v1/notifications").header(NotificationController.CUSTOMER_HEADER, "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(Matchers.containsString(NotificationController.CUSTOMER_HEADER)));
    }

    @Test
    void aNonPositiveCustomerHeaderIs400() throws Exception {
        when(notifications.listForCustomer(0L, DEFAULT_PAGE_SIZE, null))
                .thenThrow(new IllegalArgumentException("customerId must be a positive id: 0"));

        mvc.perform(get("/api/v1/notifications").header(NotificationController.CUSTOMER_HEADER, "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("customerId must be a positive id: 0"))
                .andExpect(jsonPath("$.path").value("/api/v1/notifications"));
    }

    // The catch-all advice must not answer a bad request with a 500, or this would pass
    // while the real service rejected the id for a different reason. NotificationFlowBootTests
    // makes the same call over real HTTP against the real service.

    @Test
    void withoutALimitTheConfiguredDefaultPageSizeIsUsed() throws Exception {
        when(notifications.listForCustomer(99L, DEFAULT_PAGE_SIZE, null)).thenReturn(page(null));

        mvc.perform(get("/api/v1/notifications").header(NotificationController.CUSTOMER_HEADER, "99"))
                .andExpect(status().isOk());

        Mockito.verify(notifications).listForCustomer(99L, DEFAULT_PAGE_SIZE, null);
    }

    @Test
    void limitAndCursorArePassedThroughToTheService() throws Exception {
        when(notifications.listForCustomer(99L, 5, "Y3Vyc29y")).thenReturn(page(null));

        mvc.perform(get("/api/v1/notifications")
                        .header(NotificationController.CUSTOMER_HEADER, "99")
                        .param("limit", "5")
                        .param("cursor", "Y3Vyc29y"))
                .andExpect(status().isOk());

        // The controller must not second-guess the size: the service owns the ceiling,
        // so the limit reaching it is the limit the caller asked for. Nor may it read the
        // cursor — a position only means anything to the code that issued it.
        Mockito.verify(notifications).listForCustomer(99L, 5, "Y3Vyc29y");
    }

    @Test
    void anUnreadableCursorIs400() throws Exception {
        when(notifications.listForCustomer(99L, DEFAULT_PAGE_SIZE, "not-a-cursor"))
                .thenThrow(new IllegalArgumentException("cursor is not a valid page position: not-a-cursor"));

        mvc.perform(get("/api/v1/notifications")
                        .header(NotificationController.CUSTOMER_HEADER, "99")
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("cursor")));
    }

    @Test
    void nonNumericLimitIs400() throws Exception {
        mvc.perform(get("/api/v1/notifications")
                        .header(NotificationController.CUSTOMER_HEADER, "99")
                        .param("limit", "lots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("limit")));
    }

    // --- whose inbox this is ----------------------------------------------------

    @Test
    void aCustomersInboxIsScopedByTheHeaderRatherThanByTheParameter() throws Exception {
        when(notifications.listForCustomer(42L, DEFAULT_PAGE_SIZE, null)).thenReturn(page(null, confirmed()));

        mvc.perform(get("/api/v1/notifications").header(NotificationController.CUSTOMER_HEADER, "42"))
                .andExpect(status().isOk());

        // The gateway's verified id is the scope. Nothing here reads a parameter, so
        // there is no value a client can change to reach a different row (ADR 011).
        Mockito.verify(notifications).listForCustomer(42L, DEFAULT_PAGE_SIZE, null);
    }

    @Test
    void askingForAnotherCustomersInboxIs400() throws Exception {
        // The bypass this route used to have. Customer 42 asks for 43 by parameter; the
        // answer is that the request contradicts the identity the gateway proved, and
        // the service is never asked for 43's rows at all.
        mvc.perform(get("/api/v1/notifications")
                        .header(NotificationController.CUSTOMER_HEADER, "42")
                        .param("customerId", "43"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("43")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("42")));

        Mockito.verifyNoInteractions(notifications);
    }

    @Test
    void aParameterAgreeingWithTheHeaderIsHarmless() throws Exception {
        // The parameter is redundant, not a capability, so a client still sending the id
        // it already proved keeps working. Refusing that too would break callers for no
        // security gain and make the migration a flag day.
        when(notifications.listForCustomer(42L, DEFAULT_PAGE_SIZE, null)).thenReturn(page(null, confirmed()));

        mvc.perform(get("/api/v1/notifications")
                        .header(NotificationController.CUSTOMER_HEADER, "42")
                        .param("customerId", "42"))
                .andExpect(status().isOk());

        Mockito.verify(notifications).listForCustomer(42L, DEFAULT_PAGE_SIZE, null);
    }

    @Test
    void aCustomersOwnIdAsAParameterCannotBeUsedToSkipTheHeader() throws Exception {
        // Naming yourself in the parameter is not a way to arrive without the header:
        // with no proven identity there is nothing to compare against, so the required
        // header still answers.
        mvc.perform(get("/api/v1/notifications").param("customerId", "42"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(notifications);
    }

    // --- the operator's route ----------------------------------------------------

    @Test
    void theOperatorRouteReadsWhicheverCustomerItNames() throws Exception {
        when(notifications.listForCustomer(43L, DEFAULT_PAGE_SIZE, null)).thenReturn(page(null, expired()));

        // No header involved: this route's whole job is to read a Customer the caller
        // is not. The gateway is what admits an ADMIN to it, and this method asks no
        // question about roles (ADR 011).
        mvc.perform(get("/api/v1/admin/customers/43/notifications"))
                .andExpect(status().isOk());

        Mockito.verify(notifications).listForCustomer(43L, DEFAULT_PAGE_SIZE, null);
    }

    @Test
    void theOperatorRouteStillPagesAndClampsLikeTheSelfServiceOne() throws Exception {
        when(notifications.listForCustomer(43L, 5, "Y3Vyc29y")).thenReturn(page(null, expired()));

        mvc.perform(get("/api/v1/admin/customers/43/notifications")
                        .param("limit", "5")
                        .param("cursor", "Y3Vyc29y"))
                .andExpect(status().isOk());

        Mockito.verify(notifications).listForCustomer(43L, 5, "Y3Vyc29y");
    }

    @Test
    void theSelfServiceMappingDoesNotAnswerForACustomerIdInThePath() throws Exception {
        // The two routes must not shadow each other. What matters here is not the status
        // an unmapped path produces but that nothing was read on its behalf: a sub-path
        // that reached the self-service method would be a Customer id smuggled in as a
        // path segment, which is the thing this change exists to stop.
        mvc.perform(get("/api/v1/notifications/43"));

        Mockito.verifyNoInteractions(notifications);
    }

    /** The service's answer for a page, so these tests are about the transport, not paging. */
    private NotificationPage page(String nextCursor, NotificationResponse... items) {
        return new NotificationPage(List.of(items), nextCursor);
    }

    private NotificationResponse expired() {
        return new NotificationResponse(
                2L, 202L, 99L, NotificationType.RESERVATION_EXPIRED,
                "Your reservation 202 expired before payment completed.",
                Instant.parse("2026-09-25T10:15:30Z"));
    }

    private NotificationResponse confirmed() {
        return new NotificationResponse(
                1L, 201L, 99L, NotificationType.RESERVATION_CONFIRMED,
                "Your reservation 201 is confirmed. Seats 10 are yours.",
                Instant.parse("2026-09-25T10:14:00Z"));
    }
}
