import type { RiskLevel } from "@/lib/types";

// requirements.md 15章: リスクレベルの表示。色分けは dataviz スキルのパレット思想に沿って後日調整する。
const LABELS: Record<RiskLevel, string> = {
  INFO: "情報",
  NOTICE: "注意",
  WARNING: "警告",
  CRITICAL: "危険",
};

const CLASS_NAMES: Record<RiskLevel, string> = {
  INFO: "risk-badge risk-badge--info",
  NOTICE: "risk-badge risk-badge--notice",
  WARNING: "risk-badge risk-badge--warning",
  CRITICAL: "risk-badge risk-badge--critical",
};

export function RiskBadge({ level }: { level: RiskLevel }) {
  return <span className={CLASS_NAMES[level]}>{LABELS[level]}</span>;
}
