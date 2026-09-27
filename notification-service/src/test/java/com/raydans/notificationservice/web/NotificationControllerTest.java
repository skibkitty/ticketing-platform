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

        mvc.perform(get("/api/v1/notifications").param("customerId", "99"))
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
                        .param("customerId", "99")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value("next-page-position-from-the-service"));
    }

    @Test
    void theLastPageCarriesNoCursor() throws Exception {
        when(notifications.listForCustomer(99L, 2, "Y3Vyc29y")).thenReturn(page(null, confirmed()));

        mvc.perform(get("/api/v1/notifications")
                        .param("customerId", "99")
                        .param("limit", "2")
                        .param("cursor", "Y3Vyc29y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void aPageWithNoNotificationsIsEmptyAndOffersNoCursor() throws Exception {
        when(notifications.listForCustomer(404L, DEFAULT_PAGE_SIZE, null)).thenReturn(page(null));

        mvc.perform(get("/api/v1/notifications").param("customerId", "404"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void missingCustomerIdIs400() throws Exception {
        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("customerId")))
                .andExpect(jsonPath("$.path").value("/api/v1/notifications"));
    }

    @Test
    void nonNumericCustomerIdIs400() throws Exception {
        mvc.perform(get("/api/v1/notifications").param("customerId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("customerId")));
    }

    @Test
    void nonPositiveCustomerIdIs400() throws Exception {
        when(notifications.listForCustomer(0L, DEFAULT_PAGE_SIZE, null))
                .thenThrow(new IllegalArgumentException("customerId must be a positive id: 0"));

        mvc.perform(get("/api/v1/notifications").param("customerId", "0"))
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

        mvc.perform(get("/api/v1/notifications").param("customerId", "99"))
                .andExpect(status().isOk());

        Mockito.verify(notifications).listForCustomer(99L, DEFAULT_PAGE_SIZE, null);
    }

    @Test
    void limitAndCursorArePassedThroughToTheService() throws Exception {
        when(notifications.listForCustomer(99L, 5, "Y3Vyc29y")).thenReturn(page(null));

        mvc.perform(get("/api/v1/notifications")
                        .param("customerId", "99")
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
                        .param("customerId", "99")
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("cursor")));
    }

    @Test
    void nonNumericLimitIs400() throws Exception {
        mvc.perform(get("/api/v1/notifications")
                        .param("customerId", "99")
                        .param("limit", "lots"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("limit")));
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
