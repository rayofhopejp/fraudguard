"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import type { FraudGuardEvent } from "@/lib/types";

/**
 * requirements.md 18章, 19章: 家族側操作。誰が確認・操作したかはサーバー側(acknowledgements)で記録され、
 * 他の家族にも状態が共有される想定(ここではUIのみ、実データ連携はTODO)。
 */
export function EventActions({ event }: { event: FraudGuardEvent }) {
  const [acknowledged, setAcknowledged] = useState(event.acknowledged);
  const [pending, setPending] = useState(false);

  async function handleAcknowledge() {
    setPending(true);
    try {
      // TODO: セッションのaccessTokenを渡す
      await api.acknowledgeEvent(undefined, event.eventId);
      setAcknowledged(true);
    } finally {
      setPending(false);
    }
  }

  async function handleDisconnect() {
    if (!event.metadata.callId) return;
    setPending(true);
    try {
      // requirements.md 8章: 遠隔通話切断。サーバー側で署名・ACTIVE状態・callId一致を検証する。
      await api.disconnectCall(undefined, event.deviceId, event.metadata.callId);
    } finally {
      setPending(false);
    }
  }

  return (
    <div style={{ display: "flex", gap: 8, marginTop: 16, flexWrap: "wrap" }}>
      <button onClick={handleAcknowledge} disabled={pending || acknowledged}>
        {acknowledged ? "確認済み" : "確認済みにする"}
      </button>
      {event.metadata.callId && (
        <button onClick={handleDisconnect} disabled={pending}>
          通話を切断
        </button>
      )}
      {event.metadata.phoneNumber && (
        <>
          {/* TODO: ホワイトリスト/ブラックリスト追加モーダル(lib/api.ts の addWhitelistEntry を使用) */}
          <button disabled>ホワイトリストへ追加</button>
          <button disabled>ブラックリストへ追加</button>
        </>
      )}
      {/* TODO: 「今後この警告を無視する」設定、本人へ連絡するための補助導線(電話/LINE起動リンク等) */}
    </div>
  );
}
