"use client";

import { useState, useEffect } from "react";
import AppShell from "@/components/AppShell";
import { useRealtime } from "@/hooks/useRealtime";
import { Wifi, WifiOff, Sparkles, AlertTriangle, Check, X } from "lucide-react";

const mockKpi = [
  { label: "Attente Moyenne Global", value: "1 min 48s", change: "↓ 12s", status: "good" },
  { label: "SLA du Jour (80/20)", value: "76 %", change: "Seuil 80%", status: "warning" },
  { label: "Taux d'Abandon", value: "5 %", change: "Moyenne basse", status: "good" },
  { label: "Occupation Conseillers", value: "82 %", change: "Optimal", status: "good" },
];

const initialQueues = [
  { skill: "CREDIT", length: 11, avgWaitLabel: "1 min 10" },
  { skill: "ACCOUNT", length: 2, avgWaitLabel: "0 min 30" },
  { skill: "CARD", length: 3, avgWaitLabel: "0 min 45" },
];

const mockAdvisors = [
  { name: "Amira B.", status: "BUSY", skill: "CREDIT" },
  { name: "Karim L.", status: "AVAILABLE", skill: "CARD" },
  { name: "Sonia M.", status: "BREAK", skill: "ACCOUNT" },
];

const mockAlerts = ["File CREDIT proche du seuil SLA (85%)"];

const initialRecommendation = {
  action: "Réaffecter 2 conseillers de ACCOUNT vers CREDIT",
  reason: "File crédit à 34 clients, occupation compte à 31%",
  status: "PENDING",
};

const mockEscalations = [
  { customer: "Amadou Diallo", reason: "Demande de rééchelonnement hors barème standard" },
];

const statusColor: Record<string, string> = {
  AVAILABLE: "bg-emerald-500/15 text-emerald-400 border border-emerald-500/30",
  BUSY: "bg-amber-500/15 text-amber-400 border border-amber-500/30",
  BREAK: "bg-slate-500/15 text-slate-400 border border-slate-500/30",
};

function formatWait(seconds: number) {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m} min ${s}s`;
}

export default function SupervisorPage() {
  const { lastMessage, connected } = useRealtime("ws://localhost:8081");
  const [queues, setQueues] = useState(initialQueues);
  const [recommendation, setRecommendation] = useState(initialRecommendation);

  useEffect(() => {
    if (lastMessage?.topic === "/topic/queue/CREDIT") {
      setQueues((prev) =>
        prev.map((q) =>
          q.skill === "CREDIT"
            ? {
                ...q,
                length: lastMessage.payload.length,
                avgWaitLabel: formatWait(lastMessage.payload.avgWait),
              }
            : q
        )
      );
    }
  }, [lastMessage]);

  return (
    <AppShell role="Superviseur" initials="KM" title="Centre de Supervision Temps Réel">
      {/* Top Banner Connection Badge */}
      <div className="mb-3 flex shrink-0 items-center justify-between rounded-xl border border-white/10 bg-slate-900/60 px-4 py-2 text-xs backdrop-blur-xl">
        <div className="flex items-center gap-2">
          {connected ? (
            <span className="flex items-center gap-1.5 font-semibold text-emerald-400">
              <span className="h-2 w-2 rounded-full bg-emerald-400 animate-ping" />
              <Wifi size={15} /> Flux Temps Réel Connecté (ws://localhost:8081)
            </span>
          ) : (
            <span className="flex items-center gap-1.5 font-semibold text-slate-400">
              <WifiOff size={15} /> Déconnecté (Serveur WebSocket hors ligne)
            </span>
          )}
        </div>
        <span className="text-[11px] text-slate-400 font-mono">
          Topic actif: /topic/queue/CREDIT
        </span>
      </div>

      {/* KPI Cards Row */}
      <div className="mb-4 grid shrink-0 grid-cols-2 md:grid-cols-4 gap-3">
        {mockKpi.map((k) => (
          <div key={k.label} className="glass-card rounded-2xl p-4 border border-white/10">
            <p className="text-xs font-medium text-slate-400">{k.label}</p>
            <div className="mt-1 flex items-baseline justify-between">
              <p className="font-display text-2xl font-bold text-white">{k.value}</p>
              <span className="text-[10px] font-semibold font-mono text-emerald-400">
                {k.change}
              </span>
            </div>
          </div>
        ))}
      </div>

      {/* 3 Columns Layout */}
      <div className="grid flex-1 grid-cols-1 lg:grid-cols-12 gap-3 overflow-y-auto pr-1">
        {/* Col 1: Queues & Advisors (4 cols) */}
        <div className="lg:col-span-4 glass-card rounded-2xl p-4 border border-white/10 flex flex-col gap-4">
          <div>
            <div className="flex items-center justify-between mb-3 border-b border-white/10 pb-2">
              <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">
                Files par Compétence
              </span>
              <span className="text-[10px] text-cyan-400 font-mono">Live Sync</span>
            </div>
            <div className="space-y-2">
              {queues.map((q) => (
                <div
                  key={q.skill}
                  className={`flex items-center justify-between rounded-xl border p-3 text-xs transition-all ${
                    q.skill === "CREDIT"
                      ? "border-indigo-500/40 bg-indigo-500/15 shadow-md shadow-indigo-500/10"
                      : "border-white/10 bg-slate-900/60"
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <span className="font-bold text-white">{q.skill}</span>
                    {q.skill === "CREDIT" && (
                      <span className="rounded-full bg-indigo-500/30 px-2 py-0.5 text-[9px] font-mono text-indigo-300">
                        WebSocket
                      </span>
                    )}
                  </div>
                  <span className="font-mono font-bold text-cyan-300">
                    {q.length} en attente
                  </span>
                  <span className="text-slate-400 font-mono">{q.avgWaitLabel}</span>
                </div>
              ))}
            </div>
          </div>

          <div>
            <div className="flex items-center justify-between mb-3 border-b border-white/10 pb-2">
              <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">
                Roster Conseillers
              </span>
            </div>
            <div className="space-y-2">
              {mockAdvisors.map((a) => (
                <div
                  key={a.name}
                  className="flex items-center justify-between rounded-xl border border-white/10 bg-slate-900/60 p-2.5 text-xs"
                >
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-white">{a.name}</span>
                    <span className="text-[10px] text-slate-400 font-mono">({a.skill})</span>
                  </div>
                  <span
                    className={`rounded-full px-2.5 py-0.5 text-[10px] font-semibold ${
                      statusColor[a.status]
                    }`}
                  >
                    {a.status}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Col 2: Alerts & Escalations (4 cols) */}
        <div className="lg:col-span-4 glass-card rounded-2xl p-4 border border-white/10 flex flex-col gap-4">
          <div>
            <div className="flex items-center justify-between mb-3 border-b border-white/10 pb-2">
              <span className="text-xs font-semibold uppercase tracking-wider text-slate-400 flex items-center gap-1.5">
                <AlertTriangle size={14} className="text-rose-400" /> Alertes SLA
              </span>
            </div>
            <div className="space-y-2">
              {mockAlerts.map((a, i) => (
                <div
                  key={i}
                  className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-300 font-medium"
                >
                  ⚠️ {a}
                </div>
              ))}
            </div>
          </div>

          <div>
            <div className="flex items-center justify-between mb-3 border-b border-white/10 pb-2">
              <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">
                Escalades en Attente
              </span>
            </div>
            <div className="space-y-2">
              {mockEscalations.map((e, i) => (
                <div
                  key={i}
                  className="rounded-xl border border-white/10 bg-slate-900/60 p-3 text-xs"
                >
                  <p className="font-bold text-white mb-1">{e.customer}</p>
                  <p className="text-slate-400 font-light leading-relaxed">{e.reason}</p>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Col 3: Workforce AI Recommendation (4 cols) */}
        <div className="lg:col-span-4 glass-card rounded-2xl p-5 border border-indigo-500/30 bg-gradient-to-br from-indigo-500/15 via-purple-500/10 to-cyan-500/10 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between mb-4">
              <span className="inline-flex items-center gap-1.5 text-xs font-bold text-indigo-300 uppercase tracking-wider">
                <Sparkles size={16} className="text-cyan-400" /> Workforce Manager IA
              </span>
              <span className="rounded-full bg-purple-500/20 px-2.5 py-0.5 text-[10px] font-semibold text-purple-300 border border-purple-500/30">
                IA Autonome
              </span>
            </div>

            <h4 className="text-base font-bold text-white mb-2">{recommendation.action}</h4>
            <p className="text-xs text-slate-300 leading-relaxed font-light mb-4">
              {recommendation.reason}
            </p>

            {recommendation.status === "VALIDATED" && (
              <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/15 p-3 text-xs text-emerald-300 font-medium flex items-center gap-2">
                <Check size={16} /> Recommandation appliquée avec succès !
              </div>
            )}

            {recommendation.status === "REJECTED" && (
              <div className="rounded-xl border border-slate-500/30 bg-slate-500/15 p-3 text-xs text-slate-300 font-medium flex items-center gap-2">
                <X size={16} /> Recommandation ignorée.
              </div>
            )}
          </div>

          {recommendation.status === "PENDING" && (
            <div className="flex gap-2 mt-4">
              <button
                onClick={() => setRecommendation({ ...recommendation, status: "VALIDATED" })}
                className="flex-1 flex items-center justify-center gap-1 rounded-xl bg-gradient-to-r from-indigo-500 to-cyan-500 py-2.5 text-xs font-semibold text-white shadow-md hover:brightness-110 active:scale-95 transition-all"
              >
                <Check size={14} /> Valider
              </button>
              <button
                onClick={() => setRecommendation({ ...recommendation, status: "REJECTED" })}
                className="flex-1 flex items-center justify-center gap-1 rounded-xl border border-white/15 bg-white/5 py-2.5 text-xs font-semibold text-slate-300 hover:bg-white/10 active:scale-95 transition-all"
              >
                <X size={14} /> Rejeter
              </button>
            </div>
          )}
        </div>
      </div>
    </AppShell>
  );
}