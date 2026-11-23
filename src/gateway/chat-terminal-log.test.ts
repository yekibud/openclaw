import { beforeEach, describe, expect, it, vi } from "vitest";

const warn = vi.fn();
const info = vi.fn();
vi.mock("../logging/subsystem.js", () => ({
  createSubsystemLogger: () => ({ warn, info }),
}));

const { logUnpersistedChatTerminal } = await import("./chat-terminal-log.js");

describe("logUnpersistedChatTerminal", () => {
  beforeEach(() => {
    warn.mockReset();
    info.mockReset();
  });

  it("logs an error's text and its displayed message", () => {
    logUnpersistedChatTerminal({
      runId: "wear-1",
      sessionKey: "agent:main:main",
      state: "error",
      errorMessage: "context window exceeded",
      errorKind: "timeout",
      message: { role: "assistant", content: [{ type: "text", text: "Sorry, history too long." }] },
    });
    expect(warn).toHaveBeenCalledOnce();
    const line = String(warn.mock.lastCall?.[0]);
    expect(line).toContain("runId=wear-1");
    expect(line).toContain('error="context window exceeded"');
    expect(line).toContain('message="Sorry, history too long."');
  });

  it("logs aborts at info and skips final replies, which the transcript already has", () => {
    logUnpersistedChatTerminal({
      runId: "r",
      sessionKey: "k",
      state: "aborted",
      message: { content: "partial" },
    });
    logUnpersistedChatTerminal({
      runId: "r",
      sessionKey: "k",
      state: "final",
      message: { content: "done" },
    });
    expect(info).toHaveBeenCalledOnce();
    expect(String(info.mock.lastCall?.[0])).toContain('message="partial"');
    expect(warn).not.toHaveBeenCalled();
  });

  it("caps long texts", () => {
    logUnpersistedChatTerminal({
      runId: "r",
      sessionKey: "k",
      state: "error",
      errorMessage: "x".repeat(2000),
    });
    expect(String(warn.mock.lastCall?.[0]).length).toBeLessThan(700);
  });
});
