package ai.openclaw.app.voice

import ai.openclaw.app.gateway.GatewayRequestRejected
import ai.openclaw.app.gateway.GatewaySession
import java.util.Locale

/** Older Gateways reject the optional language parameter; retry once without it. */
suspend fun requestPhoneRealtimeSessionWithLanguageFallback(
  language: String?,
  request: suspend (language: String?) -> String,
): String =
  try {
    request(language)
  } catch (err: GatewayRequestRejected) {
    if (language == null || !err.gatewayError.isUnsupportedSessionLanguageParam()) {
      throw err
    }
    request(null)
  }

private fun GatewaySession.ErrorShape.isUnsupportedSessionLanguageParam(): Boolean =
  code == "INVALID_REQUEST" &&
    message
      .lowercase(Locale.ROOT)
      .contains("invalid talk.session.create params")
