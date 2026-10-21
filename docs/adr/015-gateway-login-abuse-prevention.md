# ADR 015: Failed logins are counted on two independent keys

**Status:** Accepted

**Context:** `/auth/login` is the one route the gateway answers without a token
(ADR 002), because it is how a caller becomes authenticated in the first place. It
also had no attempt limit, which made the unauthenticated entry point to the
platform a place where a password could be guessed at whatever rate the network
allowed. The endpoint already refused to say *which* credentials were real —
constant-time comparison, and the same error body for an unknown username and a
wrong password — and that guarantee was worth nothing against an attacker who was
willing to make a million guesses to learn it.

The question is not only *how many* attempts. A limiter with a single key is
either useless or a denial-of-service waiting to happen:

- Keyed on the **username** alone, it does nothing to an attacker working on a
  thousand login names, and it hands a legitimate caller a lockout an attacker can
  trigger on purpose by failing five times in their name.
- Keyed on the **remote address** alone, twenty failed guesses from one office
  NAT locks out every caller behind it, which is the same denial of service from
  the other direction.

Either key alone is escapable: changing the username starts a *different*
account's counter while the address counter keeps counting the same attempts, and
an attacker with a botnet resets the address counter by moving.

**Decision:** Count failed logins on both keys, independently, and refuse either
one being spent. Five failures per account and twenty per remote address, in a
five-minute window, configurable under `app.gateway.login-throttle`.

```
POST /auth/login  ──►  refuseWhenThrottled(remoteAddr, username)
                           │ account key = sha256(username.strip())
                           │ address key = getRemoteAddr()   ← never X-Forwarded-For
                           └──► both within budget? compare the password
                                 wrong ─► count on both axes; refuse if either is now spent
                                 right  ─► clear the account window only; issue a token
```

**Three properties of the refusal matter more than the numbers.**

**The limit is checked before the password is read.** A caller over the limit is
refused before any credential is compared, so a correct password is refused
exactly like a wrong one. The alternative — checking the limit after — is a limiter
that answers "throttled unless my password is right", which is a password oracle
with a rate limit on it. `aCorrectPasswordIsRefusedLikeAWrongOneOnceTheAccountIsLocked`
is the test that holds this, and it is why the check is in the controller above the
comparison rather than in a filter below it.

**Failures are counted whether or not the name exists.** Nothing in the limiter
asks the credential directory whether an account is real. A counter that moved only
for real accounts would answer "this username exists" by refusing sooner than it
refuses a name nobody has — turning the limit into the oracle the error body was
built to avoid. Counting both alike means the limit looks identical from outside
in either case.

**A success clears the account window and only the account window.** Clearing the
address window too would be a way out for an attacker holding one valid
credential: alternate one real login with one wrong guess, and the host's budget
never fills. The address budget is spent by failures and only ever expires.

**The address key is `getRemoteAddr()` and nothing else — `X-Forwarded-For` is
never read.** This platform is one hop and trusts nothing in front of it (ADR 002,
ADR 012), so a forwarded header is not a fact about the caller, it is a value the
caller chose. Keying on it would make the address budget a number the attacker
sets, and the honest-looking implementation would have been the bypass: it is the
one that reads the header the operator already had in their compose file. A caller
arriving with a fresh `X-Forwarded-For` on every attempt has bought nothing, and
that is asserted from both ends — varying the login name *and* the header, so
neither axis can be doing the work the test is about.

An address the container cannot report shares one conservative `unrecorded` budget
rather than getting a budget of its own or a 500. Both alternatives are worse: a
per-caller budget for "unknown" is a budget nobody is counting against, and a 500
tells an attacker to try again.

**Consequences:** A wrong password costs an attacker five tries per account and
twenty per host, and the login surface is no longer a place to guess at network
speed. A caller who mistypes twice and then signs in correctly is not locked out
for the rest of the window, because their success handed the account back.

The honest costs, none of which are hidden:

- **Callers behind one NAT share one address budget.** Twenty failures from an
  office, a school or a carrier NAT refuses everyone's sign-in from that address
  for the rest of the window, and the fix is waiting — the address axis cannot tell
  one person's failures from another's. Raising `max-failures-per-address` is the
  knob; an authenticated identity on the request would be a better key and there
  isn't one yet, because the caller is exactly what has not been established.
- **Counters are per process.** They live in each gateway, so N replicas behind a
  load balancer give an attacker N times the configured budget, and a restart
  resets every window. A limit that has to mean one number needs a shared store —
  Redis, or the database — which is a different decision with its own failure mode
  (what happens when the store is down is a decision too, and the safe answer
  "refuse" has not been chosen here).
- **`Retry-After` is advisory.** The header says when the window ends and a
  well-behaved client waits; a client that does not is refused anyway, on the same
  budget, until the window closes. There is nothing to enforce it with, so it is a
  courtesy to callers and an inconvenience to attackers, not a control.
- **The window is fixed, not decaying.** "No more than N guesses in any five
  minutes" is the property, and a decaying average would let an attacker spend a
  whole budget in a burst and then pass the next one. The cost is the mirror
  image: a caller who fails five times just after a window boundary can fail five
  more within seconds, because the counter restarted. That is the standard trade
  for a hard cap and the right side of it to err on for a limit that exists to
  stop a burst.

**Configuration is validated, with no fallback.** `max-failures-per-account`,
`max-failures-per-address` and `window` must all be present and positive; a
missing or malformed block fails startup rather than starting a gateway whose login
surface is unprotected. A limiter that silently disables itself when misconfigured
is worse than no limiter, because the deployment looks protected.

**What holds this, and where it is asserted.** Each property is a test, and each
was mutated to confirm the test is holding it and not the shape of the code:

| Property | Test | Killed by mutating |
|---|---|---|
| The limit is asked before the password is read | `aCorrectPasswordIsRefusedLikeAWrongOneOnceTheAccountIsLocked` | checking the limit after the compare (200 instead of 429) |
| An address key is the socket, not a header | `aForwardedHeaderOfTheCallersChoosingDoesNotResetTheLimit` | keying on `X-Forwarded-For` (401 instead of 429) |
| Success refunds the account, not the host | `aSuccessfulLoginHandsBackTheAccountsBudgetAndNotTheHosts` | resetting both axes |
| Unknown names count like real ones | `theRefusalSaysNothingAboutWhetherTheLoginNameExists`, and three boot tests | counting only existing accounts (401 instead of 429) |
| One window is one window | `aForwardedHeader…`, `changingTheLoginName…`, `aCorrectPassword…` | answering one guess over the limit |
| Whitespace is not a fresh budget | `whitespaceInALoginNameDoesNotBuyAFreshBudget` | not stripping the login name |
| A spent window is forgotten | `windowsThatHaveRunOutAreForgotten` | never sweeping expired windows |
| The refusal tells a client when to come back | `retryAfterIsTheRestOfTheWindowAndNotAGuess`, `theConfiguredNumberOfWrongPasswordsIsAnsweredAndTheNextOneIsRefused` | dropping the `Retry-After` header |
| Only the login surface is counted | `noRouteOtherThanTheLoginSurfaceIsThrottled` | — (the assertions are the test) |

The limiter lives in `LoginController` rather than in a filter, and that is
consequential rather than incidental: a filter counts *arrivals*, which would spend
a real caller's budget on their own successful sign-in, and it cannot know a
credential was right, so it could never hand the budget back. Counting failures is
a question only the thing that checked the credential can answer.