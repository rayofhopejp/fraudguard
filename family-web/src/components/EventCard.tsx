import type { FraudGuardEvent } from "@/lib/types";
import { RiskBadge } from "./RiskBadge";

/**
 * requirements.md 17章[v2]: 相手の電話番号やアプリ名を出してよいのは「アプリ内(認証後)」。
 * 出してはいけないのはロック画面等のPush通知のほうで、この一覧は認証後の画面にあたる。
 *
 * 番号が分かると、家族はその場で「知っている相手か」を判断できる。
 * 一覧から1件ずつ開かないと相手が分からない状態では、通知が続いたときに追いきれない。
 */
export function EventCard({ event, deviceName }: { event: FraudGuardEvent; deviceName: string }) {
  const counterparty = describeCounterparty(event);

  return (
    <a href={`/devices/${event.deviceId}/events/${event.eventId}`} className="event-card">
      <div className="event-card__header">
        <RiskBadge level={event.riskLevel} />
        <span className="event-card__device">{deviceName}</span>
      </div>
      <p className="event-card__title">{event.title}</p>
      {counterparty && <p className="event-card__counterparty">{counterparty}</p>}
      <time className="event-card__time" dateTime={event.timestamp}>
        {new Date(event.timestamp).toLocaleString("ja-JP")}
      </time>
    </a>
  );
}

/**
 * 一覧に出す「相手」。イベントの種類によって、相手にあたるものが違う。
 * 何も分からない場合は行ごと出さない(空欄が並ぶと、かえって読みにくい)。
 */
function describeCounterparty(event: FraudGuardEvent): string | null {
  const { phoneNumber, sourceApp, appName, packageName, durationSeconds } = event.metadata;

  if (phoneNumber) {
    return durationSeconds ? `${phoneNumber}（${Math.floor(durationSeconds / 60)}分経過）` : phoneNumber;
  }
  // requirements.md 10.3章: アプリ内通話は電話番号を持たない。代わりにどのアプリかを出す。
  if (sourceApp) {
    const label = APP_LABELS[sourceApp] ?? sourceApp;
    return durationSeconds ? `${label}（${Math.floor(durationSeconds / 60)}分経過）` : label;
  }
  if (appName) return packageName ? `${appName}（${packageName}）` : appName;
  return null;
}

const APP_LABELS: Record<string, string> = {
  "jp.naver.line.android": "LINE",
  "org.telegram.messenger": "Telegram",
  "org.thoughtcrime.securesms": "Signal",
  "com.whatsapp": "WhatsApp",
};
