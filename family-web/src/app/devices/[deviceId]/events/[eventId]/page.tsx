import { api } from "@/lib/api";
import { requireAccessToken } from "@/lib/session";
import { RiskBadge } from "@/components/RiskBadge";
import { EventActions } from "./EventActions";

/**
 * requirements.md 18章: 警告詳細画面。確認済みにする/通話を切断/ホワイトリスト・ブラックリスト追加/
 * 今後無視する設定/本人へ連絡する導線/イベント履歴。
 * TODO: 専用の GET /devices/{deviceId}/events/{eventId} をサーバーに追加し、一覧からのfindではなく直接取得する。
 */
export default async function EventDetailPage({
  params,
}: {
  params: { deviceId: string; eventId: string };
}) {
  const accessToken = await requireAccessToken();
  const events = await api.listEvents(accessToken, params.deviceId).catch(() => []);
  const event = events.find((e) => e.eventId === params.eventId);

  if (!event) {
    return (
      <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
        <p>イベントが見つかりませんでした。</p>
      </main>
    );
  }

  return (
    <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <p>
        <a href={`/devices/${params.deviceId}`}>← 警告履歴</a>
      </p>
      <RiskBadge level={event.riskLevel} />
      <h1>{event.title}</h1>
      <p>{event.detail}</p>
      <p>発生時刻: {new Date(event.timestamp).toLocaleString("ja-JP")}</p>

      {/* requirements.md 17章[v2]: 詳細(相手番号・通話時間等)はここ(認証後)で表示する */}
      {event.metadata.phoneNumber && <p>相手番号: {event.metadata.phoneNumber}</p>}
      {event.metadata.durationSeconds != null && <p>通話時間: {event.metadata.durationSeconds}秒</p>}
      {event.metadata.appName && <p>アプリ: {event.metadata.appName}</p>}
      {event.metadata.messageBody && <p>SMS本文: {event.metadata.messageBody}</p>}
      {/* requirements.md 17章: 判定理由 */}
      {event.metadata.reason && <p>判定理由: {event.metadata.reason}</p>}

      <EventActions event={event} />
    </main>
  );
}
