import type { MonitoredDevice } from "@/lib/types";

/**
 * requirements.md 35章[v2]: ハートビート断で「監視が止まっている可能性」を家族へ伝える。
 *
 * 「一度も受信していない」と「受信していたのに途切れた」を分ける。
 * 登録直後はまだ1件も届いていないのが正常なのに、そこで警告色を出すと
 * 設置に失敗したように見え、設置作業をしている家族を不必要に不安にさせる。
 */
const STALE_THRESHOLD_MS = 4 * 60 * 60 * 1000; // 4時間(35.3章のサーバー側閾値に合わせる)

/** 登録直後に最初のハートビートを待てる時間。これを過ぎても届かなければ異常として扱う。 */
const FIRST_HEARTBEAT_GRACE_MS = 60 * 60 * 1000;

type Status = "ok" | "waiting" | "stale" | "revoked";

export function deviceStatus(device: MonitoredDevice, nowMillis: number = Date.now()): Status {
  // 無効化した端末は「連絡が来ない」のが正常。異常として警告すると、
  // 本当に壊れている端末と見分けがつかなくなる。
  if (device.revokedAt) return "revoked";
  if (device.lastHeartbeatAt) {
    const since = nowMillis - new Date(device.lastHeartbeatAt).getTime();
    return since > STALE_THRESHOLD_MS ? "stale" : "ok";
  }
  // 一度も届いていない場合は、登録からの経過時間で判断する。
  const sinceCreated = nowMillis - new Date(device.createdAt).getTime();
  return sinceCreated > FIRST_HEARTBEAT_GRACE_MS ? "stale" : "waiting";
}

const LABELS: Record<Status, string> = {
  ok: "監視中",
  waiting: "最初の確認を待っています",
  stale: "監視状態を確認できません",
  revoked: "見守りを停止しました",
};

export function DeviceStatusBadge({ device }: { device: MonitoredDevice }) {
  const status = deviceStatus(device);
  return (
    <span
      className={
        status === "ok" || status === "revoked"
          ? "device-status device-status--ok"
          : "device-status device-status--stale"
      }
    >
      {LABELS[status]}
    </span>
  );
}
