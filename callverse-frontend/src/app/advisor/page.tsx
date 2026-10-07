"use client";

import { useEffect, useState } from "react";
import AppShell from "@/components/AppShell";
import { apiFetch } from "@/lib/api";
import { useConversationTopic } from "@/hooks/useConversationTopic";
import { useQueueTopic } from "@/hooks/useQueueTopic";
import {
  Clock,
  Sparkles,
  Ticket,
  Percent,
  AlertTriangle,
  ArrowRightLeft,
  Send,
  User,
  ShieldAlert,
  Copy,
  PhoneIncoming,
  CheckCircle2,
} from "lucide-react";

interface Conversation {
  id: string;
  customerId: string;
  advisorId: string | null;
  skill: string;
  intent: string | null;
  channel: string;
  status: string;
  queuedAt: string;
  assignedAt: string | null;
  endedAt: string | null;
}

interface Message {
  id: number;
  conversationId: string;
  sender: "CUSTOMER" | "ADVISOR" | "SYSTEM";
  content: string;
  sentAt: string;
}

interface Card {
  id: string;
  panLast4: string;
  network: string;
  type: string;
  status: string;
  expiresOn: string;
  blockedAt?: string | null;
  blockReason?: string | null;
}

interface Account {
  id: string;
  iban: string;
  status: string;
  product: { code: string; name: string; category: string };
  cards: Card[];
}

interface Customer {
  id: string;
  externalRef: string;
  firstName: string;
  lastName: string;
  segment: string;
  region: string;
  tenureMonths: number;
  accounts: Account[];
}

interface Queue {
  skill: string;
  waiting: number;
  oldestWaitSeconds: number | null;
}

interface Transaction {
  id: string;
  accountId: string;
  type: string;
  amount: number;
  currency: string;
  label: string;
  status: string;
  bookedAt: string;
}

const mockSuggestion = {
  reply: "Retard de paiement détecté sur son prêt, un rééchelonnement est possible.",
  sources: [{ kb_article_id: "KB-Retard-Paiement-018", score: 0.91 }],
};

const priorityColor: Record<string, string> = {
  high: "bg-rose-500 shadow-rose-500/50",
  medium: "bg-amber-500 shadow-amber-500/50",
  low: "bg-slate-500 shadow-slate-500/50",
};

function skillColor(skill: string) {
  if (skill === "FRAUD") return priorityColor.high;
  if (skill === "CARDS" || skill === "CREDIT") return priorityColor.medium;
  return priorityColor.low;
}

export default function AdvisorPage() {
  const [myConversations, setMyConversations] = useState<Conversation[]>([]);
  const [queues, setQueues] = useState<Queue[]>([]);
  const [activeConvId, setActiveConvId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [draft, setDraft] = useState("");
  const [activeNotification, setActiveNotification] = useState<string | null>(null);
  const [elapsed, setElapsed] = useState(0);

  const activeConv = myConversations.find((c) => c.id === activeConvId) || null;

  const showToast = (msg: string) => {
    setActiveNotification(msg);
    setTimeout(() => setActiveNotification(null), 3000);
  };

  async function refreshMine() {
    const d = await apiFetch("/conversations/mine");
    setMyConversations(d.conversations);
  }

  async function refreshQueues() {
    const d = await apiFetch("/queues");
    setQueues(d.queues);
  }

  // Chargement initial : mes conversations + les files que je tiens
  useEffect(() => {
    refreshMine();
    refreshQueues();
  }, []);

  // Quand je change de conversation active : charger messages + fiche client + transactions
  useEffect(() => {
    if (!activeConv) {
      setMessages([]);
      setCustomer(null);
      setTransactions([]);
      return;
    }
    apiFetch(`/conversations/${activeConv.id}/messages?limit=50`).then((d) => setMessages(d.messages));
    apiFetch(`/customers/${activeConv.customerId}`).then(setCustomer);
    apiFetch(`/customers/${activeConv.customerId}/transactions?count=10`).then((d) =>
      setTransactions(d.transactions)
    );
  }, [activeConv?.id]);

  // Chrono SLA côté client, calculé depuis queuedAt (jamais envoyé par le serveur)
  useEffect(() => {
    if (!activeConv) return;
    const start = new Date(activeConv.queuedAt).getTime();
    const tick = () => setElapsed(Math.floor((Date.now() - start) / 1000));
    tick();
    const interval = setInterval(tick, 1000);
    return () => clearInterval(interval);
  }, [activeConv?.id, activeConv?.queuedAt]);

  // Temps réel : messages et changements de statut sur la conversation ouverte
  const convEvent = useConversationTopic(activeConvId);
  useEffect(() => {
    if (!convEvent) return;
    if (convEvent.type === "MESSAGE_POSTED" && convEvent.sender) {
      setMessages((prev) => [
        ...prev,
        {
          id: convEvent.messageId!,
          conversationId: convEvent.conversationId,
          sender: convEvent.sender!,
          content: convEvent.content!,
          sentAt: convEvent.sentAt!,
        },
      ]);
    }
    if (convEvent.type === "STATUS_CHANGED") {
      refreshMine();
      showToast(`Conversation → ${convEvent.status}`);
    }
  }, [convEvent]);

  // Temps réel : les files que je tiens (une par compétence)
  const myFirstQueueEvent = useQueueTopic(queues[0]?.skill ?? null);
  useEffect(() => {
    if (myFirstQueueEvent) refreshQueues();
  }, [myFirstQueueEvent]);

  async function takeNext(skill: string) {
  try {
    const result = await apiFetch(`/queues/${skill}/next`, { method: "POST" });
    if (result) {
      await refreshMine();
      setActiveConvId(result.id);
      showToast(`Appel pris : file ${skill}`);
    } else {
      showToast(`Rien en attente sur ${skill} pour l'instant`);
    }
  } catch (err) {
    const code = err instanceof Error ? err.message : "UNKNOWN";
    if (code === "ADVISOR_UNAVAILABLE") {
      showToast("Tu as déjà atteint ton nombre maximum d'appels simultanés");
    } else if (code === "ADVISOR_PROFILE_NOT_FOUND") {
      showToast("Ce compte n'a pas de profil conseiller configuré");
    } else {
      showToast(`Erreur : ${code}`);
    }
  }
}

  async function handleSendMessage(e?: React.FormEvent) {
    if (e) e.preventDefault();
    if (!draft.trim() || !activeConv) return;
    await apiFetch(`/conversations/${activeConv.id}/messages`, {
      method: "POST",
      body: JSON.stringify({ content: draft }),
    });
    setDraft("");
    // Le message s'affichera via le topic temps réel (événement MESSAGE_POSTED)
  }

  const handleApplySuggestion = () => {
    setDraft(mockSuggestion.reply);
    showToast("Suggestion IA copiée dans le champ de saisie !");
  };

  async function handleBlockCard(cardId: string) {
    await apiFetch(`/cards/${cardId}/block`, {
      method: "POST",
      body: JSON.stringify({ reason: "FRAUD_SUSPECTED" }),
    });
    if (activeConv) {
      const d = await apiFetch(`/customers/${activeConv.customerId}`);
      setCustomer(d);
    }
    showToast("Carte bloquée pour suspicion de fraude");
  }

  async function handleOpenTicket() {
    if (!activeConv) return;
    await apiFetch("/tickets", {
      method: "POST",
      body: JSON.stringify({
        customerId: activeConv.customerId,
        conversationId: activeConv.id,
        category: activeConv.skill,
        title: "Demande du client",
        severity: 3,
      }),
    });
    showToast(`Nouveau ticket ouvert pour ${customer?.firstName ?? "le client"}`);
  }

  async function handleEscalate() {
    if (!activeConv) return;
    await apiFetch(`/conversations/${activeConv.id}/escalations`, {
      method: "POST",
      body: JSON.stringify({ reason: "Nécessite l'intervention d'un superviseur" }),
    });
    showToast("Dossier escaladé au superviseur en charge");
  }

  async function handleResolve() {
    if (!activeConv) return;
    await apiFetch(`/conversations/${activeConv.id}/resolve`, { method: "POST" });
    setActiveConvId(null);
    await refreshMine();
    showToast("Conversation résolue");
  }

  function handleTransfer() {
    // Pas d'endpoint de transfert documenté côté backend pour l'instant — reste un mock volontaire.
    showToast("Transfert non disponible : endpoint pas encore implémenté côté backend");
  }

  const mm = String(Math.floor(elapsed / 60)).padStart(2, "0");
  const ss = String(elapsed % 60).padStart(2, "0");

  const allCards = customer?.accounts.flatMap((a) => a.cards) ?? [];

  return (
    <AppShell role="Conseiller" initials="AB" title="Poste de Travail Conseiller">
      {/* Toast notification */}
      {activeNotification && (
        <div className="absolute top-4 right-6 z-50 rounded-xl border border-indigo-500/40 bg-indigo-900/90 px-4 py-2.5 text-xs font-semibold text-white shadow-2xl backdrop-blur-xl animate-in fade-in slide-in-from-top-3">
          ✨ {activeNotification}
        </div>
      )}

      {/* Main 3-Column Layout */}
      <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-12 gap-3 overflow-hidden">
        {/* Left Column: Files + mes conversations (3 cols) */}
        <div className="lg:col-span-3 glass-card flex flex-col rounded-2xl p-3 border border-white/10 overflow-hidden gap-4">
          <div>
            <div className="mb-2 flex items-center justify-between border-b border-white/10 pb-2">
              <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">
                Mes compétences
              </span>
              <span className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse" />
            </div>
            <div className="flex flex-col gap-2">
              {queues.map((q) => (
                <button
                  key={q.skill}
                  onClick={() => takeNext(q.skill)}
                  className="flex items-center justify-between rounded-xl border border-white/5 bg-slate-900/40 p-2.5 text-left hover:bg-white/5 transition-all"
                >
                  <div className="flex items-center gap-2.5">
                    <span className={`h-2.5 w-2.5 rounded-full ${skillColor(q.skill)}`} />
                    <span className="text-xs font-medium text-slate-200">{q.skill}</span>
                  </div>
                  <span className="flex items-center gap-1 rounded-full bg-indigo-500/20 px-2 py-0.5 text-[10px] font-semibold text-indigo-300">
                    <PhoneIncoming size={11} /> {q.waiting}
                  </span>
                </button>
              ))}
              {queues.length === 0 && (
                <p className="text-[11px] text-slate-500 italic">Aucune compétence assignée.</p>
              )}
            </div>
          </div>

          <div className="flex-1 overflow-y-auto">
            <div className="mb-2 border-b border-white/10 pb-2">
              <span className="text-xs font-semibold uppercase tracking-wider text-slate-400">
                Mes conversations ({myConversations.length})
              </span>
            </div>
            <div className="flex flex-col gap-2 pr-1">
              {myConversations.map((c) => (
                <button
                  key={c.id}
                  onClick={() => setActiveConvId(c.id)}
                  className={`flex items-center justify-between rounded-xl p-2.5 transition-all text-left ${
                    c.id === activeConvId
                      ? "border border-indigo-500/40 bg-indigo-500/15 shadow-lg shadow-indigo-500/10"
                      : "border border-white/5 bg-slate-900/40 hover:bg-white/5"
                  }`}
                >
                  <div className="flex items-center gap-2.5">
                    <span className={`h-2.5 w-2.5 rounded-full ${skillColor(c.skill)}`} />
                    <span
                      className={`text-xs font-medium ${
                        c.id === activeConvId ? "text-white font-semibold" : "text-slate-300"
                      }`}
                    >
                      {c.skill} · {c.status}
                    </span>
                  </div>
                  {c.id === activeConvId && (
                    <span className="rounded-full bg-indigo-500/20 px-2 py-0.5 text-[10px] font-semibold text-indigo-300">
                      En cours
                    </span>
                  )}
                </button>
              ))}
              {myConversations.length === 0 && (
                <p className="text-[11px] text-slate-500 italic">
                  Prends un appel dans une file ci-dessus.
                </p>
              )}
            </div>
          </div>
        </div>

        {/* Center Column: Live Conversation & AI Assistant (6 cols) */}
        <div className="lg:col-span-6 glass-card flex flex-col rounded-2xl p-4 border border-white/10 overflow-hidden">
          {!activeConv ? (
            <div className="flex flex-1 items-center justify-center text-sm text-slate-500">
              Sélectionne ou prends une conversation pour commencer.
            </div>
          ) : (
            <>
              {/* Header */}
              <div className="mb-3 flex shrink-0 items-center justify-between border-b border-white/10 pb-3">
                <div className="flex items-center gap-3">
                  <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br from-rose-500 to-amber-500 font-bold text-xs text-white shadow-md">
                    {customer ? `${customer.firstName[0]}${customer.lastName[0]}` : "…"}
                  </div>
                  <div>
                    <span className="text-sm font-bold text-white flex items-center gap-2">
                      {customer ? `${customer.firstName} ${customer.lastName}` : "Chargement…"}
                      <span className="rounded-full bg-indigo-500/20 px-2 py-0.5 text-[10px] font-semibold text-indigo-300 border border-indigo-500/30">
                        {customer?.segment}
                      </span>
                    </span>
                    <span className="text-[11px] text-slate-400 block">
                      {activeConv.skill} · {customer?.region}
                    </span>
                  </div>
                </div>
                <div className="flex items-center gap-1.5 rounded-xl border border-rose-500/30 bg-rose-500/10 px-3 py-1.5 text-xs font-mono font-semibold text-rose-400">
                  <Clock size={14} /> {mm}:{ss}
                </div>
              </div>

              {/* Messages Stream */}
              <div className="mb-3 flex flex-1 flex-col gap-2.5 overflow-y-auto pr-1">
                {messages.map((m) => (
                  <div
                    key={m.id}
                    className={`max-w-[80%] rounded-2xl px-3.5 py-2.5 text-xs leading-relaxed ${
                      m.sender === "CUSTOMER"
                        ? "self-start bg-slate-900/80 text-slate-200 border border-white/10 shadow-sm"
                        : m.sender === "ADVISOR"
                        ? "self-end bg-gradient-to-r from-indigo-600 to-indigo-500 text-white shadow-md shadow-indigo-500/20"
                        : "self-center text-slate-500 text-[11px] italic"
                    }`}
                  >
                    {m.content}
                  </div>
                ))}
              </div>

              {/* AI Suggestion Box — reste en mock, pas encore fourni par le backend */}
              <div className="mb-3 shrink-0 rounded-2xl border border-indigo-500/30 bg-gradient-to-br from-indigo-500/15 via-purple-500/10 to-cyan-500/10 p-3 shadow-lg">
                <div className="flex items-center justify-between mb-1.5">
                  <p className="flex items-center gap-1.5 text-xs font-bold text-indigo-300">
                    <Sparkles size={14} className="text-cyan-400" /> Suggestion IA (RAG)
                  </p>
                  <button
                    onClick={handleApplySuggestion}
                    className="flex items-center gap-1 rounded-lg bg-indigo-500/30 px-2.5 py-1 text-[11px] font-semibold text-indigo-200 border border-indigo-500/40 hover:bg-indigo-500/50 transition-colors"
                  >
                    <Copy size={12} /> Utiliser
                  </button>
                </div>
                <p className="text-xs text-slate-200 font-light leading-relaxed">{mockSuggestion.reply}</p>
                <p className="mt-1.5 text-[10px] text-slate-400 font-mono">
                  Source : {mockSuggestion.sources[0].kb_article_id} · Indice de confiance :{" "}
                  {mockSuggestion.sources[0].score * 100}%
                </p>
              </div>

              {/* Message Draft Input */}
              <form onSubmit={handleSendMessage} className="flex gap-2 shrink-0">
                <input
                  type="text"
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  placeholder={`Écrire un message à ${customer?.firstName ?? "…"}`}
                  className="glass-input flex-1 rounded-xl px-3.5 py-2.5 text-xs"
                />
                <button
                  type="submit"
                  className="flex items-center gap-1 rounded-xl bg-gradient-to-r from-indigo-500 to-cyan-500 px-4 py-2.5 text-xs font-semibold text-white shadow-md hover:brightness-110 active:scale-95 transition-all"
                >
                  <Send size={14} /> Envoyer
                </button>
              </form>
            </>
          )}
        </div>

        {/* Right Column: Customer Profile 360 (3 cols) */}
        <div className="lg:col-span-3 glass-card flex flex-col justify-between rounded-2xl p-4 border border-white/10 overflow-hidden">
          {!customer ? (
            <div className="flex flex-1 items-center justify-center text-xs text-slate-500">
              Aucun client sélectionné.
            </div>
          ) : (
            <>
              <div>
                <span className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-3 block flex items-center gap-1.5 border-b border-white/10 pb-2">
                  <User size={15} className="text-indigo-400" /> Fiche Client 360°
                </span>

                <div className="space-y-3 text-xs">
                  <div className="flex items-center justify-between py-1.5 border-b border-white/10">
                    <span className="text-slate-400">Produit Actif</span>
                    <span className="font-semibold text-white">
                      {customer.accounts[0]?.product.name ?? "—"}
                    </span>
                  </div>
                  <div className="flex items-center justify-between py-1.5 border-b border-white/10">
                    <span className="text-slate-400">Ancienneté</span>
                    <span className="font-mono font-semibold text-cyan-300">
                      {customer.tenureMonths} mois
                    </span>
                  </div>
                  <div className="flex items-center justify-between py-1.5 border-b border-white/10">
                    <span className="text-slate-400">Segment</span>
                    <span className="rounded-full bg-indigo-500/20 px-2.5 py-0.5 font-semibold text-indigo-300 border border-indigo-500/30">
                      {customer.segment}
                    </span>
                  </div>
                </div>

                {/* Cartes — action reelle de blocage */}
                <div className="mt-4">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-400 mb-2 block">
                    Cartes
                  </span>
                  <div className="space-y-1.5">
                    {allCards.map((card) => (
                      <div
                        key={card.id}
                        className="flex items-center justify-between rounded-lg border border-white/10 bg-slate-900/60 px-2.5 py-2"
                      >
                        <span className="font-mono text-[11px] text-slate-300">
                          •••• {card.panLast4}
                        </span>
                        {card.status === "BLOCKED" ? (
                          <span className="flex items-center gap-1 text-[10px] text-rose-400">
                            <ShieldAlert size={11} /> Bloquée
                          </span>
                        ) : (
                          <button
                            onClick={() => handleBlockCard(card.id)}
                            className="text-[10px] font-semibold text-rose-300 hover:text-rose-200"
                          >
                            Bloquer
                          </button>
                        )}
                      </div>
                    ))}
                  </div>
                </div>

                {/* Dernières transactions */}
                <div className="mt-4">
                  <span className="text-[11px] font-semibold uppercase tracking-wider text-slate-400 mb-2 block">
                    Dernières opérations
                  </span>
                  <div className="space-y-1.5">
                    {transactions.slice(0, 5).map((t) => (
                      <div key={t.id} className="flex items-center justify-between text-[11px]">
                        <span className="text-slate-400 truncate max-w-[120px]">{t.label}</span>
                        <span
                          className={`font-mono font-semibold ${
                            t.amount < 0 ? "text-slate-300" : "text-emerald-400"
                          }`}
                        >
                          {t.amount.toFixed(2)} {t.currency}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>

              <button
                onClick={handleResolve}
                className="mt-4 flex items-center justify-center gap-2 rounded-xl border border-emerald-500/30 bg-emerald-500/10 px-3.5 py-2 text-xs font-semibold text-emerald-300 hover:bg-emerald-500/20 transition-all active:scale-95"
              >
                <CheckCircle2 size={15} /> Résoudre la conversation
              </button>
            </>
          )}
        </div>
      </div>

      {/* Action Bar Footer */}
      <div className="mt-3 flex shrink-0 flex-wrap gap-2 pt-2 border-t border-white/10">
        <button
          onClick={handleOpenTicket}
          disabled={!activeConv}
          className="flex items-center gap-2 rounded-xl border border-white/15 bg-white/5 px-3.5 py-2 text-xs font-semibold text-slate-200 hover:bg-white/10 transition-all active:scale-95 disabled:opacity-40"
        >
          <Ticket size={15} className="text-indigo-400" /> Créer un ticket
        </button>
        <button
          onClick={() => showToast("Geste commercial : en attente de validation côté équipe")}
          disabled
          className="flex items-center gap-2 rounded-xl border border-white/15 bg-white/5 px-3.5 py-2 text-xs font-semibold text-slate-200 opacity-40"
        >
          <Percent size={15} className="text-emerald-400" /> Geste commercial
        </button>
        <button
          onClick={handleEscalate}
          disabled={!activeConv}
          className="flex items-center gap-2 rounded-xl border border-rose-500/30 bg-rose-500/10 px-3.5 py-2 text-xs font-semibold text-rose-300 hover:bg-rose-500/20 transition-all active:scale-95 disabled:opacity-40"
        >
          <AlertTriangle size={15} /> Escalader
        </button>
        <button
          onClick={handleTransfer}
          disabled={!activeConv}
          className="flex items-center gap-2 rounded-xl border border-white/15 bg-white/5 px-3.5 py-2 text-xs font-semibold text-slate-200 hover:bg-white/10 transition-all active:scale-95 disabled:opacity-40"
        >
          <ArrowRightLeft size={15} className="text-cyan-400" /> Transférer
        </button>
      </div>
    </AppShell>
  );
}