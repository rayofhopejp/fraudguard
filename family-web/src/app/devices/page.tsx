import { api } from "@/lib/api";
import { requireAccessToken } from "@/lib/session";
import { DeviceStatusBadge } from "@/components/DeviceStatusBadge";
import { AddDeviceForm } from "./AddDeviceForm";

/** requirements.md 21章: 家族ユーザーが所属する監視対象端末の一覧(多対多)。 */
export default async function DevicesPage() {
  const accessToken = await requireAccessToken();
  // 取得失敗を空配列にすると「端末が無い」と区別がつかない。見守りの有無に関わる表示なので、
  // 失敗は失敗として出す。
  let devices: Awaited<ReturnType<typeof api.listDevices>> = [];
  let error: string | null = null;
  try {
    devices = await api.listDevices(accessToken);
  } catch (e) {
    error = e instanceof Error ? e.message : "端末一覧を取得できませんでした";
  }

  return (
    <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1>監視対象端末</h1>
        {/* ログイン後はrootが/devicesへ飛ぶため、ここから辿れないとログアウトできない。 */}
        <a href="/login">アカウント</a>
      </div>
      <AddDeviceForm />
      {error && <p style={{ color: "#b71c1c" }}>端末一覧を取得できませんでした: {error}</p>}
      {!error && devices.length === 0 && <p>登録された端末がありません。上のフォームから追加してください。</p>}
      <ul style={{ listStyle: "none", padding: 0 }}>
        {devices.map((device) => (
          <li key={device.deviceId} style={{ marginBottom: 12 }}>
            <a href={`/devices/${device.deviceId}`}>
              <strong>{device.name}</strong>{" "}
              <DeviceStatusBadge device={device} />
            </a>
          </li>
        ))}
      </ul>
    </main>
  );
}
