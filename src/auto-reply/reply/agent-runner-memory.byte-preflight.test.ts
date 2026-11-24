import { expect, it, vi } from "vitest";
import { projectCompactionAccountingPatch } from "../../config/sessions/session-entry-projection.js";
import {
  SESSION_TOTAL_TOKENS_VERSION,
  type InternalSessionEntry,
} from "../../config/sessions/types.js";
import { runSessionCompactionIfNeeded } from "./agent-runner-memory.js";
import { createTestFollowupRun } from "./agent-runner.test-fixtures.js";

const { compact, account, preflightWarn } = vi.hoisted(() => ({
  compact: vi.fn(),
  account: vi.fn(),
  preflightWarn: vi.fn(),
}));

// mock-isolation: Exercise preflight admission without starting a model runtime.
vi.mock("../../agents/embedded-agent.js", () => ({ compactEmbeddedAgentSession: compact }));
// mock-isolation: This preflight never dispatches a memory-flush model turn.
vi.mock("../../agents/embedded-agent-runner/run-entry.js", () => ({}));
// mock-isolation: Keep byte-pressure admission independent of token estimates and database workers.
vi.mock("./agent-runner-memory-transcript-context.js", () => ({
  estimateContextTokensFromSessionTranscript: async () => undefined,
  readSessionLogSnapshot: async () => ({}),
}));
// mock-isolation: Supply raw accounting at the transcript reader boundary.
vi.mock("../../gateway/session-transcript-readers.js", () => ({
  readSessionTranscriptAccountingAsync: async () => ({ byteSize: 102_400 }),
}));
// mock-isolation: Observe the preflight warnings that explain a bounded turn.
vi.mock("../../logging/subsystem.js", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../logging/subsystem.js")>();
  return {
    ...actual,
    createSubsystemLogger: (name: string) => {
      const logger = actual.createSubsystemLogger(name);
      return name === "auto-reply/preflight-compaction"
        ? Object.assign(Object.create(logger), { warn: preflightWarn })
        : logger;
    },
  };
});
// mock-isolation: Observe admission accounting without starting database workers.
vi.mock("./session-updates.js", () => ({ incrementCompactionCount: account }));

it.each([
  { runtime: "openclaw", ok: false, compacted: false },
  { runtime: "openclaw", ok: true, compacted: true },
])(
  "continues built-in byte preflight with bounded context (ok=$ok, compacted=$compacted)",
  async ({ runtime, ok, compacted }) => {
    compact.mockReset().mockResolvedValue({ ok, compacted, reason: "fixture declines compaction" });
    account
      .mockReset()
      .mockImplementation(
        async (
          params: Parameters<typeof import("./session-updates.js").incrementCompactionCount>[0],
        ) => {
          const entry = params.expectedSession;
          if (!entry || !params.sessionStore || !params.sessionKey) {
            throw new Error("Missing compaction accounting target");
          }
          const current: InternalSessionEntry = { ...entry, updatedAt: 1 };
          const updated: InternalSessionEntry = {
            ...current,
            ...projectCompactionAccountingPatch(current, params),
          };
          params.sessionStore[params.sessionKey] = updated;
          return params.amount ?? 1;
        },
      );
    preflightWarn.mockReset();
    const followupRun = createTestFollowupRun({ provider: "openai", model: "gpt-5.5" });
    const notice = vi.fn();
    const params: Parameters<typeof runSessionCompactionIfNeeded>[0] = {
      cfg: { agents: { defaults: { compaction: { maxActiveTranscriptBytes: "32kb" } } } },
      followupRun,
      defaultModel: "gpt-5.5",
      sessionKey: "main",
      sessionEntry: {
        sessionId: "session",
        updatedAt: 1,
        totalTokens: 100,
        totalTokensFresh: true,
        totalTokensVersion: SESSION_TOTAL_TOKENS_VERSION,
      },
      agentHarnessId: runtime,

      onCompactionNotice: notice,
    };
    const entry = await runSessionCompactionIfNeeded(params);

    expect(entry).toMatchObject({
      sessionId: "session",
      compactionCount: compacted ? 1 : 0,
      transcriptByteCompactionLatch: {
        activeBytes: 102_400,
        maxBytes: 32_768,
        sessionId: "session",
      },
    });
    expect(notice.mock.calls.map(([phase]) => phase)).toContain("context_bounded");
    if (!ok) {
      expect(preflightWarn).toHaveBeenCalledWith(
        expect.stringMatching(
          /^context bounded: cause=compaction_failed:fixture declines compaction .*activeBytes=102400 maxBytes=32768 /,
        ),
      );
    }
    preflightWarn.mockClear();
    notice.mockClear().mockRejectedValueOnce(new Error("fixture notice delivery failed"));
    await runSessionCompactionIfNeeded({
      ...params,
      sessionEntry: { ...params.sessionEntry!, ...entry },
    });
    expect(compact).toHaveBeenCalledOnce();
    expect(notice.mock.calls.map(([phase]) => phase)).toContain("context_bounded");
    expect(preflightWarn).toHaveBeenCalledWith(
      expect.stringMatching(/^context bounded: cause=byte_compaction_latched .*latchBytes=102400 /),
    );
  },
);
