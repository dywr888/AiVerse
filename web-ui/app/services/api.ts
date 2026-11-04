import ky from "ky";
import type { FavoriteDto, MessageDto, SessionDto } from "~/types/core";

const api = ky.create({ prefixUrl: "/api" });

export async function listSessions(): Promise<SessionDto[]> {
  return api.get("sessions").json();
}

export async function getSession(id: string): Promise<SessionDto> {
  return api.get(`sessions/${id}`).json();
}

export async function listMessages(sessionId: string): Promise<MessageDto[]> {
  return api.get(`sessions/${sessionId}/messages`).json();
}

export async function sendMessage(
  sessionId: string,
  content: string,
): Promise<void> {
  await api.post(`sessions/${sessionId}/send`, { json: { content } });
}

export async function listFavorites(): Promise<FavoriteDto[]> {
  return api.get("favorites").json();
}

export async function deleteFavorite(id: string): Promise<void> {
  await api.delete(`favorites/${id}`);
}

/** SSE 订阅会话流式事件。返回取消函数。 */
export function subscribeSessionStream(
  sessionId: string,
  onEvent: (event: { type: string; data?: unknown }) => void,
  onError: (error: Error) => void,
): () => void {
  const controller = new AbortController();
  let closed = false;
  const url = `/api/sessions/${sessionId}/stream`;

  (async () => {
    try {
      const response = await fetch(url, { signal: controller.signal });
      if (!response.ok) throw new Error(`SSE ${response.status}`);
      const reader = response.body!.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      while (!closed) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split("\n\n");
        buffer = parts.pop() ?? "";
        for (const part of parts) {
          const dataLines = part
            .split("\n")
            .filter((l) => l.startsWith("data:"));
          if (dataLines.length === 0) continue;
          const data = dataLines.map((l) => l.slice(5).trim()).join("\n");
          try {
            onEvent(JSON.parse(data));
          } catch {
            onEvent({ type: "raw", data });
          }
        }
      }
    } catch (e) {
      if (!closed) onError(e instanceof Error ? e : new Error(String(e)));
    }
  })();

  return () => {
    closed = true;
    controller.abort();
  };
}
