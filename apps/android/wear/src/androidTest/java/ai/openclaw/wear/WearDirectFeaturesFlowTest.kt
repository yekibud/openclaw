package ai.openclaw.wear

import ai.openclaw.app.LocationMode
import ai.openclaw.app.gateway.DeviceIdentityStore
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.wear.shared.WearRealtimeTalkStatus
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Direct-mode features against an in-process mock Gateway: the watch's node session answers
 * location.get, and the watch-owned Talk client runs a relay session on its own operator socket.
 * Only the Gateway is controlled; launcher, runtime, audio devices and stores are real.
 */
@RunWith(AndroidJUnit4::class)
class WearDirectFeaturesFlowTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val device = UiDevice.getInstance(instrumentation)
  private val output by lazy { File(instrumentation.targetContext.getExternalFilesDir(null), "direct-features").apply { mkdirs() } }

  @Test
  fun locationGetIsAnsweredOverTheWatchNodeSession() {
    instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
    val gateway = FeatureGateway()
    withDirectRuntime(gateway, locationMode = LocationMode.WhileUsing) { runtime ->
      awaitState("direct connected") { runtime.state.value.connected }
      awaitState("node session shares location") { runtime.state.value.locationShared }
      val result = gateway.invokeResult.get(30, TimeUnit.SECONDS)
      capture("location-01-connected")
      assertEquals("node.invoke.result echoes the invoke id", "inv-location-1", result["id"]!!.jsonPrimitive.content)
      val ok = result["ok"]!!.jsonPrimitive.content.toBoolean()
      val payload = result["payload"] as? JsonObject
      assertTrue("location.get must answer with a fix, got $result", ok && payload != null && payload["lat"] != null && payload["lon"] != null)
      assertEquals("gps", payload!!["source"]!!.jsonPrimitive.content)
      assertTrue("accuracy reported", payload["accuracyMeters"] != null)
    }
  }

  @Test
  fun talkRunsARelaySessionOnTheWatch() {
    instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
    val gateway = FeatureGateway()
    withDirectRuntime(gateway, locationMode = LocationMode.Off) { runtime ->
      awaitState("direct connected") { runtime.state.value.connected && runtime.state.value.sessionKey != null }
      instrumentation.runOnMainSync { runtime.startTalk() }
      awaitState("relay session created") { gateway.talkCreated.get() }
      awaitState("Talk listening") { runtime.state.value.talk.status == WearRealtimeTalkStatus.LISTENING }
      awaitState("microphone frames reach the relay", 20_000) { gateway.audioFrames.get() >= 2 }
      capture("talk-01-listening")
      gateway.speak("Hi from the relay")
      awaitState("reply plays on the watch") { runtime.state.value.talkPlaying || runtime.state.value.talk.status == WearRealtimeTalkStatus.SPEAKING }
      awaitState("assistant transcript projected") {
        runtime.state.value.talk.conversation
          .any { it.text == "Hi from the relay" }
      }
      capture("talk-02-speaking")
      awaitState("playback drains back to listening", 20_000) { !runtime.state.value.talkPlaying && runtime.state.value.talk.status == WearRealtimeTalkStatus.LISTENING }
      instrumentation.runOnMainSync { runtime.stopTalk() }
      awaitState("relay session closed on the Gateway") { gateway.talkClosed.get() }
      awaitState("Talk inactive") { !runtime.state.value.talk.active }
      assertEquals("no Talk failure", false, runtime.state.value.talk.status == WearRealtimeTalkStatus.ERROR)
    }
  }

  private fun withDirectRuntime(
    gateway: FeatureGateway,
    locationMode: LocationMode,
    block: (WearDirectRuntime) -> Unit,
  ) {
    val app = instrumentation.targetContext.applicationContext as WearApplication
    val preferenceName = "direct-features-" + UUID.randomUUID()
    val store = WearGatewayStore(app.getSharedPreferences(preferenceName, Context.MODE_PRIVATE))
    val endpoint = GatewayEndpoint.manual("127.0.0.1", gateway.server.port, false).copy(name = "Feature fixture")
    store.replace(WearGatewaySetup(endpoint, "fixture-bootstrap"), DeviceIdentityStore.withPrefs(app, store).loadOrCreate().deviceId)
    store.setLocationMode(locationMode)
    val job = SupervisorJob()
    val runtime = WearDirectRuntime(app, CoroutineScope(job + Dispatchers.Default), store)
    val field = WearApplication::class.java.getDeclaredField("directRuntime\$delegate").apply { isAccessible = true }
    val previous = field.get(app)
    field.set(app, lazyOf(runtime))
    var activity: MainActivity? = null
    try {
      activity = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
      block(runtime)
    } finally {
      activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
      instrumentation.waitForIdleSync()
      runtime.disconnect()
      runBlocking { withTimeout(15_000) { job.cancelAndJoin() } }
      field.set(app, previous)
      app.deleteSharedPreferences(preferenceName)
      gateway.server.shutdown()
    }
  }

  private fun awaitState(
    label: String,
    timeoutMs: Long = 15_000,
    predicate: () -> Boolean,
  ) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
    if (!predicate()) capture("failed-$label")
    assertTrue(label, predicate())
  }

  private fun capture(name: String) {
    SystemClock.sleep(600)
    device.takeScreenshot(File(output, "$name.png"))
  }

  /** Mock Gateway: bootstrap + operator + location node sessions, one location invoke, one relay Talk session. */
  private class FeatureGateway {
    val invokeResult = CompletableFuture<JsonObject>()
    val talkCreated = AtomicBoolean(false)
    val talkClosed = AtomicBoolean(false)
    val audioFrames = AtomicInteger()
    private val operatorSocket = AtomicReference<WebSocket>()
    private val relaySessionId = "relay-fixture-1"

    fun speak(text: String) {
      val socket = checkNotNull(operatorSocket.get()) { "operator socket not connected" }

      fun event(payload: JsonObject) =
        socket.send(
          buildJsonObject {
            put("type", "event")
            put("event", "talk.event")
            put("payload", payload)
          }.toString(),
        )
      event(
        buildJsonObject {
          put("relaySessionId", relaySessionId)
          put("type", "transcript")
          put("role", "user")
          put("text", "hello watch")
          put("final", true)
        },
      )
      val frame = Base64.encodeToString(ByteArray(24_000 * 2 / 10), Base64.NO_WRAP) // 100 ms of 24 kHz PCM16 silence
      repeat(4) {
        event(
          buildJsonObject {
            put("relaySessionId", relaySessionId)
            put("type", "audio")
            put("audioBase64", frame)
          },
        )
      }
      event(
        buildJsonObject {
          put("relaySessionId", relaySessionId)
          put("type", "mark")
          put("markName", "reply-1")
        },
      )
      event(
        buildJsonObject {
          put("relaySessionId", relaySessionId)
          put("type", "transcript")
          put("role", "assistant")
          put("text", text)
          put("final", true)
        },
      )
    }

    val server =
      MockWebServer().apply {
        dispatcher =
          object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
              MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                  override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                  ) {
                    webSocket.send("""{"type":"event","event":"connect.challenge","payload":{"nonce":"feature-fixture","ts":1700000000123}}""")
                  }

                  override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                  ) {
                    val frame = Json.parseToJsonElement(text).jsonObject
                    val method = frame["method"]!!.jsonPrimitive.content
                    val params = frame["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val payload: JsonElement =
                      when (method) {
                        "connect" -> {
                          val role = params["role"]!!.jsonPrimitive.content
                          val caps = (params["caps"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                          when {
                            role == "node" && "location" in caps -> {
                              // The watch's second session: answer, then ask it for a fix like the Gateway would.
                              webSocket.send(
                                buildJsonObject {
                                  put("type", "res")
                                  put("id", frame.getValue("id"))
                                  put("ok", true)
                                  put("payload", Json.parseToJsonElement("""{"auth":{"role":"node","deviceToken":"fixture-node","scopes":[]},"snapshot":{"sessionDefaults":{"mainSessionKey":"agent:main:main"}}}"""))
                                }.toString(),
                              )
                              webSocket.send(
                                buildJsonObject {
                                  put("type", "event")
                                  put("event", "node.invoke.request")
                                  put(
                                    "payload",
                                    buildJsonObject {
                                      put("id", "inv-location-1")
                                      put("nodeId", "watch-fixture")
                                      put("command", "location.get")
                                      put("paramsJSON", """{"timeoutMs":8000,"desiredAccuracy":"precise"}""")
                                      put("timeoutMs", 8000)
                                    },
                                  )
                                }.toString(),
                              )
                              return
                            }

                            role == "node" -> {
                              Json.parseToJsonElement("""{"auth":{"role":"node","deviceToken":"fixture-node","scopes":[],"deviceTokens":[{"role":"operator","deviceToken":"fixture-operator","scopes":["operator.read","operator.write","operator.approvals"]}]},"snapshot":{"sessionDefaults":{"mainSessionKey":"agent:main:main"}}}""")
                            }

                            else -> {
                              operatorSocket.set(webSocket)
                              Json.parseToJsonElement("""{"features":{"methods":["chat.message.get","talk.session.create"]},"auth":{"role":"operator","deviceToken":"fixture-operator","scopes":["operator.read","operator.write","operator.approvals"]},"snapshot":{"sessionDefaults":{"mainSessionKey":"agent:main:main"}}}""")
                            }
                          }
                        }

                        "sessions.list" -> {
                          Json.parseToJsonElement("""{"sessions":[{"key":"agent:main:main","displayName":"Feature fixture"}]}""")
                        }

                        "sessions.messages.subscribe" -> {
                          Json.parseToJsonElement("""{"subscribed":true,"key":"agent:main:main","agentId":"main","approvalReplay":{"sessionKey":"agent:main:main","updatedAtMs":1,"truncated":false,"approvals":[]}}""")
                        }

                        "chat.history" -> {
                          buildJsonObject { put("messages", JsonArray(emptyList())) }
                        }

                        "node.invoke.result" -> {
                          invokeResult.complete(params)
                          buildJsonObject {}
                        }

                        "talk.session.create" -> {
                          assertEquals("realtime", params["mode"]!!.jsonPrimitive.content)
                          assertEquals("gateway-relay", params["transport"]!!.jsonPrimitive.content)
                          assertEquals("agent-consult", params["brain"]!!.jsonPrimitive.content)
                          talkCreated.set(true)
                          buildJsonObject { put("relaySessionId", relaySessionId) }
                        }

                        "talk.session.appendAudio" -> {
                          assertEquals(relaySessionId, params["sessionId"]!!.jsonPrimitive.content)
                          assertTrue(params["audioBase64"]!!.jsonPrimitive.content.isNotEmpty())
                          audioFrames.incrementAndGet()
                          buildJsonObject {}
                        }

                        "talk.session.acknowledgeMark" -> {
                          buildJsonObject {}
                        }

                        "talk.session.close" -> {
                          talkClosed.set(true)
                          buildJsonObject {}
                        }

                        else -> {
                          error("Unexpected feature fixture method: $method")
                        }
                      }
                    webSocket.send(
                      buildJsonObject {
                        put("type", "res")
                        put("id", frame.getValue("id"))
                        put("ok", true)
                        put("payload", payload)
                      }.toString(),
                    )
                  }

                  override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                  ) {
                    webSocket.close(code, reason)
                  }
                },
              )
          }
        start()
      }
  }
}
