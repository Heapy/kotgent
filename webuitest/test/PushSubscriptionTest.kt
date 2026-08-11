package io.kotgent.webuitest

import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PushSubscriptionTest {

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


    private fun onPushPage(trace: String, block: (Page, List<Pair<String, String>>) -> Unit) {
        Harness(EMPTY_SCENARIO).use { harness ->
            onChromium { browser ->
                browser.fineContext().use { context ->
                    // A real push service is unavailable; replace the platform before app code runs.
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

        // A valid encoded P-256 point is required to reach subscribe rather than fail decoding.
        const val FAKE_VAPID_KEY =
            "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSvpeZ4jVCbfnjxYNGnfKdI-XvKPvVYVGQ"

        const val FAKE_ENDPOINT = "https://push.example.invalid/endpoint/kotgent-test"
        const val FAKE_P256DH = "p256dh-of-this-device"
        const val FAKE_AUTH = "auth-of-this-device"

        val HANDSHAKE_STEPS = listOf(
            "permission",
            "register",
            "GET $VAPID_KEY_PATH",
            "subscribe userVisibleOnly=true",
            "POST $SUBSCRIBE_PATH",
        )

        val OFF_STEPS = listOf("POST $UNSUBSCRIBE_PATH", "getRegistration", "getSubscription", "unsubscribe")

        const val RESET_TRACE = "() => { window.__pushTrace.length = 0; }"
        const val SAW_SUBSCRIBE_POST =
            """() => window.__pushTrace.indexOf("POST /push/subscribe") >= 0"""
        const val SAW_BROWSER_UNSUBSCRIBE =
            """() => window.__pushTrace.indexOf("unsubscribe") >= 0"""

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
