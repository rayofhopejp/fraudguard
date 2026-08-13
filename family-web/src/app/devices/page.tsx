import { api } from "@/lib/api";
import { requireAccessToken } from "@/lib/session";
import { DeviceStatusBadge } from "@/components/DeviceStatusBadge";

/** requirements.md 21章: 家族ユーザーが所属する監視対象端末の一覧(多対多)。 */
export default async function DevicesPage() {
  const accessToken = await requireAccessToken();
  const devices = await api.listDevices(accessToken).catch(() => []);

  return (
    <main style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <h1>監視対象端末</h1>
      {devices.length === 0 && <p>登録された端末がありません。</p>}
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
