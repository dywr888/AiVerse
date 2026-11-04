import { useEffect, useRef, useState } from "react";
import { useParams } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, Send, Wrench } from "lucide-react";
import { listMessages, sendMessage, subscribeSessionStream } from "~/services/api";
import type { MessageDto } from "~/types/core";
import { Link } from "react-router";
import dayjs from "dayjs";

function MessageBubble({ m }: { m: MessageDto }) {
  const isUser = m.role === "USER";
  const isTool = m.role === "TOOL";
  if (isTool) {
    return (
      <div className="flex justify-center">
        <div
          className="flex max-w-[90%] items-start gap-2 rounded-lg border border-zinc-800 bg-zinc-900 px-3 py-2 text-xs text-zinc-400"
        >
          <Wrench className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <div className="min-w-0">
            <div className="font-medium text-zinc-300">
              {m.toolName ?? "工具"}
              {m.isError ? "（失败）" : ""}
            </div>
            {m.content && (
              <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-words font-mono text-[11px] text-zinc-400">
                {m.content}
              </pre>
            )}
          </div>
        </div>
      </div>
    );
  }
  return (
    <div className={`flex ${isUser ? "justify-end" : "justify-start"}`}>
      <div
        className={`max-w-[85%] whitespace-pre-wrap break-words rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${
          isUser
            ? "bg-blue-600 text-white"
            : "border border-zinc-800 bg-zinc-900 text-zinc-100"
        }`}
      >
        {m.content || "（空）"}
      </div>
    </div>
  );
}

export default function Chat() {
  const { id } = useParams();
  const [streaming, setStreaming] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const [pending, setPending] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  const { data: messages, refetch } = useQuery({
    queryKey: ["messages", id],
    queryFn: () => listMessages(id!),
    enabled: !!id,
  });

  useEffect(() => {
    if (!id) return;
    const unsub = subscribeSessionStream(
      id,
      (event) => {
        if (event.type === "streaming") {
          setStreaming(String((event.data as { content?: string } | undefined)?.content ?? ""));
        } else if (event.type === "message" || event.type === "completed" || event.type === "error") {
          refetch();
          setStreaming(null);
        }
      },
      () => refetch(),
    );
    return unsub;
  }, [id]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streaming]);

  async function onSend() {
    const content = input.trim();
    if (!content || !id || pending) return;
    setInput("");
    setPending(true);
    try {
      await sendMessage(id, content);
      refetch();
    } catch (e) {
      console.error(e);
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex h-dvh flex-col bg-zinc-950 text-zinc-100">
      <header className="flex items-center gap-2 border-b border-zinc-800 px-4 py-3">
        <Link to="/" className="text-zinc-400 hover:text-zinc-200">
          <ArrowLeft className="h-4 w-4" />
        </Link>
        <h1 className="text-sm font-semibold">会话</h1>
        <span className="ml-auto text-xs text-zinc-500">{id?.slice(0, 8)}</span>
      </header>
      <main className="flex-1 overflow-y-auto px-4 py-4">
        <div className="mx-auto max-w-2xl space-y-3">
          {messages?.map((m) => <MessageBubble key={m.id} m={m} />)}
          {streaming !== null && (
            <div className="flex justify-start">
              <div className="max-w-[85%] whitespace-pre-wrap break-words rounded-2xl border border-zinc-800 bg-zinc-900 px-4 py-2.5 text-sm leading-relaxed text-zinc-100">
                {streaming}
              </div>
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      </main>
      <footer className="border-t border-zinc-800 px-4 py-3">
        <div className="mx-auto flex max-w-2xl items-end gap-2">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                onSend();
              }
            }}
            rows={2}
            placeholder="输入消息，Enter 发送，Shift+Enter 换行"
            className="max-h-40 flex-1 resize-none rounded-xl border border-zinc-800 bg-zinc-900 px-3 py-2 text-sm text-zinc-100 placeholder:text-zinc-600 focus:outline-none focus:border-zinc-600"
          />
          <button
            onClick={onSend}
            disabled={pending || !input.trim()}
            className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-blue-600 text-white disabled:opacity-40"
          >
            <Send className="h-4 w-4" />
          </button>
        </div>
      </footer>
    </div>
  );
}
