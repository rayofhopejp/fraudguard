import type { FraudGuardEvent } from "@/lib/types";
import { RiskBadge } from "./RiskBadge";

/**
 * requirements.md 17章[v2]: 一覧表示は「監視端末名・警告レベル・イベント種別・発生時刻」程度に留め、
 * 電話番号やSMS本文などの詳細は詳細画面(認証後)側で見せる方針に合わせている。
 * TODO: 19章の「誰が確認・操作したか」の表示、確認済み操作ボタン。
 */
export function EventCard({ event, deviceName }: { event: FraudGuardEvent; deviceName: string }) {
  return (
    <a href={`/devices/${event.deviceId}/events/${event.eventId}`} className="event-card">
      <div className="event-card__header">
        <RiskBadge level={event.riskLevel} />
        <span className="event-card__device">{deviceName}</span>
      </div>
      <p className="event-card__title">{event.title}</p>
      <time className="event-card__time" dateTime={event.timestamp}>
        {new Date(event.timestamp).toLocaleString("ja-JP")}
      </time>
    </a>
  );
}
