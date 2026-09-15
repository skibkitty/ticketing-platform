# ADR 001: Spring Cloud Gateway MVC instead of the reactive Gateway

**Status:** Accepted

**Context:** Every service in this repo is a standard Spring MVC (servlet
stack) application. Spring Cloud's original Gateway runs on WebFlux/Netty.

**Decision:** Use `spring-cloud-starter-gateway-mvc`.

**Consequences:** One concurrency model across the platform, no reactive
debugging skills required for gateway work. Tradeoff: newer, less proven at
very high concurrency than the reactive Gateway's non-blocking I/O — an
acceptable tradeoff at this project's scale.
