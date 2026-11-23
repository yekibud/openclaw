import { createSubsystemLogger } from "../logging/subsystem.js";

const terminalLog = createSubsystemLogger("gateway/chat-terminal");
const TERMINAL_LOG_TEXT_CHARS = 500;

function terminalMessageText(message: Record<string, unknown> | undefined): string {
  const content = message?.content;
  if (typeof content === "string") {
    return content;
  }
  if (!Array.isArray(content)) {
    return "";
  }
  return content
    .map((part) =>
      part && typeof part === "object" && typeof (part as { text?: unknown }).text === "string"
        ? (part as { text: string }).text
        : "",
    )
    .join(" ");
}

type ChatTerminalLogFields = {
  runId: string;
  sessionKey: string;
  state: string;
  errorMessage?: string;
  errorKind?: string;
  stopReason?: string;
  message?: Record<string, unknown>;
};

// Clients show or speak an error or abort's texts (the watch reads its message aloud), but neither reaches the
// transcript, so both are logged here to stay findable afterwards.
export function logUnpersistedChatTerminal(payload: ChatTerminalLogFields): void {
  if (payload.state !== "error" && payload.state !== "aborted") {
    return;
  }
  const error = (payload.errorMessage ?? "").slice(0, TERMINAL_LOG_TEXT_CHARS);
  const shown = terminalMessageText(payload.message).slice(0, TERMINAL_LOG_TEXT_CHARS);
  const line =
    `chat run ${payload.state} runId=${payload.runId} sessionKey=${payload.sessionKey} ` +
    `kind=${payload.errorKind ?? "-"} stopReason=${payload.stopReason ?? "-"} ` +
    `error=${JSON.stringify(error)} message=${JSON.stringify(shown)}`;
  if (payload.state === "error") {
    terminalLog.warn(line);
  } else {
    terminalLog.info(line);
  }
}
