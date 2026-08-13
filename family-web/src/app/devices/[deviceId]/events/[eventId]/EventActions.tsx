"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useSession } from "next-auth/react";
import { api } from "@/lib/api";
import type { FraudGuardEvent } from "@/lib/types";

/**
 * requirements.md 18章, 19章: 家族側操作。誰が確認・操作したかはサーバー側(acknowledgements)で記録され、
 * 他の家族にも状態が共有される。
 */
export function EventActions({ event }: { event: FraudGuardEvent }) {
  const { data: session } = useSession();
  const accessToken = session?.accessToken;
  const router = useRouter();

  const [acknowledged, setAcknowledged] = useState(event.acknowledged);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function run(label: string, action: () => Promise<unknown>) {
    setPending(true);
    setMessage(null);
    try {
      await action();
      setMessage(`${label}しました`);
      router.refresh();
    } catch {
      setMessage(`${label}に失敗しました`);
    } finally {
      setPending(false);
    }
  }

  const phoneNumber = event.metadata.phoneNumber;

  return (
    <div style={{ marginTop: 16 }}>
      <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
        <button
          disabled={pending || acknowledged}
          onClick={() =>
            run("確認済みに", async () => {
              await api.acknowledgeEvent(accessToken, event.eventId);
              setAcknowledged(true);
            })
          }
        >
          {acknowledged ? "確認済み" : "確認済みにする"}
        </button>

        {/* requirements.md 8章: 遠隔通話切断。サーバー側で署名・ACTIVE状態・callId一致を検証する。 */}
        {event.metadata.callId && (
          <button
            disabled={pending}
            onClick={() =>
              run("切断を要求", () => api.disconnectCall(accessToken, event.deviceId, event.metadata.callId!))
            }
          >
            通話を切断
          </button>
        )}

        {phoneNumber && (
          <>
            <button
              disabled={pending}
              onClick={() =>
                run("ホワイトリストへ追加", () =>
                  api.addWhitelistEntry(accessToken, event.deviceId, {
                    phoneNumber,
                    displayName: phoneNumber,
                  }),
                )
              }
            >
              ホワイトリストへ追加
            </button>
            <button
              disabled={pending}
              onClick={() =>
                run("ブラックリストへ追加", () =>
                  api.addBlacklistEntry(accessToken, event.deviceId, {
                    phoneNumber,
                    reason: event.title,
                  }),
                )
              }
            >
              ブラックリストへ追加
            </button>
            {/* requirements.md 18章: 本人へ連絡するための補助導線 */}
            <a href={`tel:${phoneNumber}`}>
              <button disabled={pending}>この番号にかける</button>
            </a>
          </>
        )}
      </div>

      {message && <p style={{ marginTop: 8 }}>{message}</p>}

      {/* TODO: requirements.md 18章「今後この警告を無視する」設定(サーバー側APIが未実装)。 */}
    </div>
  );
}
