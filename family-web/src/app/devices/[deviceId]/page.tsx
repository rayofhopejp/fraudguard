import { api } from "@/lib/api";
import { EventCard } from "@/components/EventCard";

/** requirements.md 17章, 19章: 特定端末の警告履歴一覧。 */
export default async function DeviceEventsPage({ params }: { params: { deviceId: string } }) {
  const events = await api.listEvents(undefined, params.deviceId).catch(() => []);

  return (
    <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <p>
        <a href="/devices">← 端末一覧</a> ・ <a href={`/devices/${params.deviceId}/whitelist`}>ホワイトリスト管理</a>
      </p>
      <h1>警告履歴</h1>
      {events.length === 0 && <p>イベントはまだありません。</p>}
      {events.map((event) => (
        <EventCard key={event.eventId} event={event} deviceName={params.deviceId} />
      ))}
    </main>
  );
}
