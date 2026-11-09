package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewaySession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

fun nodeInvokeError(
  code: String,
  message: String,
): GatewaySession.InvokeResult = GatewaySession.InvokeResult.error(code, "$code: $message")

/** Parses invoke params into a JSON object, returning null for absent/malformed input. */
fun parseJsonParamsObject(paramsJson: String?): JsonObject? {
  if (paramsJson.isNullOrBlank()) return null
  return try {
    Json.parseToJsonElement(paramsJson).asObjectOrNull()
  } catch (_: Throwable) {
    null
  }
}
