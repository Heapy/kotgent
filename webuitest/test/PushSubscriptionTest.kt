package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The browser half of Web Push: the ORDER the handshake runs in, and the two arguments in it that decide
 * whether the subscription is usable at all.
 *
 * ## What is testable here and what is not
 * Delivery is not. A push service is a third party, a headless Chromium has none, and nothing in this
 * repository can make one wake a service worker — so the worker's own `push` handler, its payload-less
 * `/sessions` fetch and its abort deadline stay on the manual checklist, and `PushRoutesTest` owns the
 * daemon's side of the wire. What is left over is not decoration: `lib/push.js` is a SEQUENCE, and every
 * claim below is about which call happens before which, or about one argument's value. Those are exactly
 * what a script running in a real browser can answer, and they are the claims whose failure is silent —
 * a device that is simply never reachable, with no error anywhere.
 *
 * ## Why the platform is faked and the daemon is not
 * `navigator.serviceWorker` and `PushManager` are replaced before the page loads, because a real
 * `pushManager.subscribe` needs the push service that does not exist here and would reject; the whole
 * flow would then stop one step short of every assertion. The DAEMON's routes are real HTTP — the harness
 * mounts no `/push` routes (it is built without a push store), so the two the flow calls are fulfilled by
 * Playwright at their real addresses, which keeps `lib/api.js`'s prefix, credentials and JSON handling in
 * the path under test.
 *
 * The fakes are recorders as well as stand-ins: every step appends to one `window.__pushTrace`, and so
 * does a wrapper around `fetch`, so the browser's calls and the daemon's calls are ordered against each
 * other in a single list rather than in two that cannot be compared.
 */
class PushSubscriptionTest {

    /**
     * The permission prompt is requested BEFORE the first `await`, and the subscription is `userVisibly`.
     *
     * The ordering is the iOS user-gesture invariant, and it is the one rule in this file with a whole
     * paragraph in `CLAUDE.md`: Safari only allows `Notification.requestPermission()` while the page is
     * still inside the click's task, and awaiting service-worker registration first is enough to leave it.
     * The failure mode is that the prompt never appears — on the one platform where push is the entire
     * point of the PWA, and on no platform a test could otherwise run. So the trace's FIRST entry has to
     * be the permission, and every other step has to come after it.
     *
     * `userVisibleOnly: true` is the second claim. Chromium refuses a subscription without it outright,
     * which sounds self-enforcing until you notice that the refusal here would look exactly like every
     * other "this browser cannot do push" downgrade `subscribe()` answers `false` for — a silent
     * fallback to in-tab notifications, on a locked phone that will never see one.
     *
     * The rest of the assertion is the sequence itself: register the worker, ask the daemon for its VAPID
     * key BEFORE subscribing (the browser will not mint a subscription without an application server
     * key), then hand the daemon the endpoint. A flow that POSTed before it subscribed would register an
     * endpoint that does not exist yet.
     */
    @Test
    fun theSubscriptionHandshakeAsksPermissionFirstAndSubscribesUserVisibly() {
        onPushPage("push-subscribe") { page, bodies ->
            page.evaluate(RESET_TRACE)
            page.locator("#notify-toggle").click()

            page.waitForFunction(SAW_SUBSCRIBE_POST)
            val trace = traceOf(page)
            assertEquals(
                "permission",
                trace.firstOrNull(),
                "the permission prompt must be requested before the flow's first await, or iOS leaves the " +
                    "click's user-activation task and never shows it; the trace was $trace",
            )
            assertEquals(
                listOf(
                    "permission",
                    "register",
                    "GET $VAPID_KEY_PATH",
                    "subscribe userVisibleOnly=true",
                    "POST $SUBSCRIBE_PATH",
                ),
                trace.filter { it in HANDSHAKE_STEPS },
                "the handshake runs permission → worker → VAPID key → subscribe → daemon, in that order",
            )

            val posted = bodies.firstOrNull { it.first == SUBSCRIBE_PATH }?.second.orEmpty()
            for (field in listOf(FAKE_ENDPOINT, "\"p256dh\":\"$FAKE_P256DH\"", "\"auth\":\"$FAKE_AUTH\"")) {
                assertTrue(
                    posted.contains(field),
                    "the daemon is handed the endpoint and both keys; $field was missing from $posted",
                )
            }
            assertThat(page.locator("#notify-toggle")).hasAttribute("aria-pressed", "true")
        }
    }

    /**
     * Turning it off deletes at the DAEMON first, and only then looks the browser subscription up.
     *
     * `unsubscribe()` launches the daemon delete from its remembered endpoints before awaiting
     * `getRegistration()` / `getSubscription()`, and the order is the whole safety property: neither
     * browser call is cancellable, and a device whose daemon row survives keeps being sent notifications
     * after the operator turned them off. A rewrite that "tidied" the flow into
     * look-up-then-delete would pass every other test in the suite.
     *
     * The remembered endpoint is what the ON pass above left behind, so this runs as its continuation
     * rather than from a fixture: what is being asserted is that the delete needs no lookup to know where
     * to send, which is only meaningful if the memory is the real one.
     */
    @Test
    fun turningItOffDeletesAtTheDaemonBeforeItLooksTheBrowserSubscriptionUp() {
        onPushPage("push-unsubscribe") { page, _ ->
            page.locator("#notify-toggle").click()
            page.waitForFunction(SAW_SUBSCRIBE_POST)

            page.evaluate(RESET_TRACE)
            page.locator("#notify-toggle").click()
            page.waitForFunction(SAW_BROWSER_UNSUBSCRIBE)

            val trace = traceOf(page).filter { it in OFF_STEPS }
            assertEquals(
                OFF_STEPS,
                trace,
                "the daemon delete is LAUNCHED before the uncancellable browser lookup and drop; got $trace",
            )
            assertThat(page.locator("#notify-toggle")).hasAttribute("aria-pressed", "false")
        }
    }

    // --- fixture ------------------------------------------------------------------------------------

    /**
     * A signed-in page whose push platform is faked and whose two daemon push routes are fulfilled.
     *
     * The `empty` scenario: nothing here reads a session, and the smallest fixture is the one least
     * likely to make an unrelated failure look like this one's.
     */
    private fun onPushPage(trace: String, block: (Page, List<Pair<String, String>>) -> Unit) {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    context.addInitScript(PUSH_PLATFORM)
                    context.traced(trace) {
                        context.loginWithTicket(harness.ticket, harness.baseUrl)
                        val page = context.newPage()
                        val bodies = CopyOnWriteArrayList<Pair<String, String>>()
                        page.route("**$API_PREFIX$VAPID_KEY_PATH") { route ->
                            route.fulfill(
                                Route.FulfillOptions()
                                    .setContentType("application/json")
                                    .setBody("""{"key":"$FAKE_VAPID_KEY"}"""),
                            )
                        }
                        for (path in listOf(SUBSCRIBE_PATH, UNSUBSCRIBE_PATH)) {
                            page.route("**$API_PREFIX$path") { route ->
                                bodies.add(path to route.request().postData().orEmpty())
                                route.fulfill(
                                    Route.FulfillOptions().setContentType("application/json").setBody("{}"),
                                )
                            }
                        }
                        page.navigate("${harness.baseUrl}/")
                        assertThat(page.locator("#notify-toggle")).isVisible()
                        block(page, bodies)
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun traceOf(page: Page): List<String> =
        (page.evaluate("() => window.__pushTrace.slice()") as List<Any?>).map { it.toString() }

    private companion object {
        const val API_PREFIX = "/api/v1"
        const val VAPID_KEY_PATH = "/push/vapid-key"
        const val SUBSCRIBE_PATH = "/push/subscribe"
        const val UNSUBSCRIBE_PATH = "/push/unsubscribe"

        /** A real 65-byte P-256 point in base64url, so `decodeBase64Url` has something valid to decode. */
        const val FAKE_VAPID_KEY =
            "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSvpeZ4jVCbfnjxYNGnfKdI-XvKPvVYVGQ"

        const val FAKE_ENDPOINT = "https://push.example.invalid/endpoint/kotgent-test"
        const val FAKE_P256DH = "p256dh-of-this-device"
        const val FAKE_AUTH = "auth-of-this-device"

        /** The five ordered steps of the ON handshake; every other trace entry is filtered out. */
        val HANDSHAKE_STEPS = listOf(
            "permission",
            "register",
            "GET $VAPID_KEY_PATH",
            "subscribe userVisibleOnly=true",
            "POST $SUBSCRIBE_PATH",
        )

        /** The OFF ordering, in the order it must happen in. */
        val OFF_STEPS = listOf("POST $UNSUBSCRIBE_PATH", "getRegistration", "getSubscription", "unsubscribe")

        const val RESET_TRACE = "() => { window.__pushTrace.length = 0; }"
        const val SAW_SUBSCRIBE_POST =
            """() => window.__pushTrace.indexOf("POST /push/subscribe") >= 0"""
        const val SAW_BROWSER_UNSUBSCRIBE =
            """() => window.__pushTrace.indexOf("unsubscribe") >= 0"""

        /**
         * The faked push platform, installed before the page loads.
         *
         * Everything `supported()` asks for exists (`serviceWorker`, `PushManager`, `Notification`), the
         * permission starts at `default` so `ensurePermission` really prompts, and the fake active worker
         * answers `syncWorkerPushPreference`'s `MessageChannel` handshake — without that reply the ON flow
         * gives up two steps before the VAPID key and every assertion here would be about nothing.
         *
         * `fetch` is wrapped rather than observed from Kotlin so the daemon's calls land in the SAME trace
         * as the browser's, which is what makes "before" and "after" comparable at all.
         */
        val PUSH_PLATFORM = """
            (() => {
              const trace = [];
              window.__pushTrace = trace;

              const nativeFetch = window.fetch.bind(window);
              window.fetch = (input, init) => {
                const url = typeof input === "string" ? input : (input && input.url) || "";
                const method = ((init && init.method) || "GET").toUpperCase();
                const path = String(url).replace(/^https?:\/\/[^/]+/, "").replace("/api/v1", "");
                if (path.indexOf("/push/") === 0) trace.push(method + " " + path);
                return nativeFetch(input, init);
              };

              const Notifications = function () {};
              Notifications.permission = "default";
              Notifications.requestPermission = () => {
                trace.push("permission");
                Notifications.permission = "granted";
                return Promise.resolve("granted");
              };
              window.Notification = Notifications;

              let current = null;
              let requestedKey = null;
              const subscription = {
                endpoint: "$FAKE_ENDPOINT",
                get options() { return { applicationServerKey: requestedKey }; },
                toJSON: () => ({
                  endpoint: "$FAKE_ENDPOINT",
                  keys: { p256dh: "$FAKE_P256DH", auth: "$FAKE_AUTH" },
                }),
                unsubscribe: () => {
                  trace.push("unsubscribe");
                  current = null;
                  return Promise.resolve(true);
                },
              };
              const pushManager = {
                subscribe: (options) => {
                  trace.push("subscribe userVisibleOnly=" + !!(options && options.userVisibleOnly));
                  requestedKey = options && options.applicationServerKey;
                  current = subscription;
                  return Promise.resolve(subscription);
                },
                getSubscription: () => {
                  trace.push("getSubscription");
                  return Promise.resolve(current);
                },
              };
              const worker = {
                postMessage: (message, transfer) => {
                  const port = transfer && transfer[0];
                  if (port) port.postMessage(true);
                },
              };
              const registration = { active: worker, pushManager: pushManager, scope: "/" };
              Object.defineProperty(navigator, "serviceWorker", {
                configurable: true,
                value: {
                  controller: null,
                  register: () => {
                    trace.push("register");
                    return Promise.resolve(registration);
                  },
                  getRegistration: () => {
                    trace.push("getRegistration");
                    return Promise.resolve(registration);
                  },
                  get ready() { return Promise.resolve(registration); },
                  addEventListener: () => {},
                  removeEventListener: () => {},
                },
              });
              window.PushManager = function () {};
            })();
        """.trimIndent()
    }
}
