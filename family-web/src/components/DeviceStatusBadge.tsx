import type { MonitoredDevice } from "@/lib/types";

/**
 * requirements.md 35章[v2]: ハートビート断で「監視が止まっている可能性」を家族へ伝える。
 * ここでは簡易的にlastHeartbeatAtの経過時間だけで判定する(実際の閾値はサーバー側の設定に合わせる)。
 */
const STALE_THRESHOLD_MS = 4 * 60 * 60 * 1000; // 4時間(35.3章の例に合わせた仮値)

export function DeviceStatusBadge({ device }: { device: MonitoredDevice }) {
  const isStale =
    !device.lastHeartbeatAt || Date.now() - new Date(device.lastHeartbeatAt).getTime() > STALE_THRESHOLD_MS;

  return (
    <span className={isStale ? "device-status device-status--stale" : "device-status device-status--ok"}>
      {isStale ? "監視状態を確認できません" : "監視中"}
    </span>
  );
}
