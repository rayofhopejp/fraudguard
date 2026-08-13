import { api } from "@/lib/api";
import { requireAccessToken } from "@/lib/session";
import { EventCard } from "@/components/EventCard";

/** requirements.md 17章, 19章: 特定端末の警告履歴一覧。 */
export default async function DeviceEventsPage({ params }: { params: { deviceId: string } }) {
  const accessToken = await requireAccessToken();

  // 取得失敗を空配列にすると「イベントが無い」と区別がつかない。失敗は失敗として出す。
  let events: Awaited<ReturnType<typeof api.listEvents>> | null = null;
  let error: string | null = null;
  try {
    events = await api.listEvents(accessToken, params.deviceId);
  } catch (e) {
    error = e instanceof Error ? e.message : "警告履歴を取得できませんでした";
  }

  // 一覧に端末IDが出ていた。家族にとって意味のある文字列ではないので名前を引く。
  const device = await api
    .listDevices(accessToken)
    .then((devices) => devices.find((d) => d.deviceId === params.deviceId))
    .catch(() => undefined);
  const deviceName = device?.name ?? "監視端末";

  return (
    <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <p>
        <a href="/devices">← 端末一覧</a> ・ <a href={`/devices/${params.deviceId}/whitelist`}>ホワイトリスト管理</a> ・{" "}
        <a href={`/devices/${params.deviceId}/blacklist`}>ブラックリスト管理</a> ・{" "}
        <a href={`/devices/${params.deviceId}/members`}>見守っている家族</a>
      </p>
      <h1>{deviceName} の警告履歴</h1>
      {error && <p style={{ color: "var(--color-critical)" }}>警告履歴を取得できませんでした: {error}</p>}
      {events?.length === 0 && <p>イベントはまだありません。</p>}
      {events?.map((event) => (
        <EventCard key={event.eventId} event={event} deviceName={deviceName} />
      ))}
    </main>
  );
}
