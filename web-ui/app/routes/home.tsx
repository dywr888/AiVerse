import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router";
import { MessageSquare } from "lucide-react";
import { listSessions } from "~/services/api";
import dayjs from "dayjs";

export default function Home() {
  const { data: sessions, isLoading, error } = useQuery({
    queryKey: ["sessions"],
    queryFn: listSessions,
  });

  return (
    <div className="min-h-dvh bg-zinc-950 text-zinc-100">
      <header className="sticky top-0 border-b border-zinc-800 bg-zinc-950/90 px-4 py-3 backdrop-blur">
        <h1 className="text-base font-semibold">AiVerse</h1>
      </header>
      <main className="mx-auto max-w-2xl px-4 py-4">
        {isLoading && <p className="text-sm text-zinc-500">加载中…</p>}
        {error && (
          <p className="text-sm text-red-400">加载失败：{String(error)}</p>
        )}
        {sessions && sessions.length === 0 && (
          <p className="text-sm text-zinc-500">暂无会话，请先在 App 里发起对话。</p>
        )}
        <ul className="space-y-2">
          {sessions?.map((s) => (
            <li key={s.id}>
              <Link
                to={`/c/${s.id}`}
                className="flex items-center gap-3 rounded-xl border border-zinc-800 bg-zinc-900 px-4 py-3 transition-colors hover:bg-zinc-800/70"
              >
                <MessageSquare className="h-4 w-4 shrink-0 text-zinc-500" />
                <span className="min-w-0 flex-1 truncate text-sm">
                  {s.title || "未命名会话"}
                </span>
                <span className="shrink-0 text-xs text-zinc-500">
                  {dayjs(s.updatedAt).format("MM-DD HH:mm")}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      </main>
    </div>
  );
}
