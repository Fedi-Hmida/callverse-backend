"use client";

import { useState } from "react";
import AppShell from "@/components/AppShell";
import { Download, MessageCircle, AlertTriangle, CreditCard, Clock, CheckCircle2, Shield, X, Send, Sparkles } from "lucide-react";

const mockContract = { plan: "Crédit Auto", status: "ACTIVE", since: "12/08/2022" };

const mockInvoices = [
  { period: "Août 2026", amount: "412,50 €", status: "PAID" },
  { period: "Juillet 2026", amount: "412,50 €", status: "PAID" },
  { period: "Juin 2026", amount: "412,50 €", status: "OVERDUE" },
];

const mockTickets = [
  { title: "Demande de report d'échéance", status: "IN_PROGRESS" },
  { title: "Question sur le taux", status: "RESOLVED" },
];

const mockIncident = { active: true, channel: "l'application mobile", eta: "14h00" };

const statusColor: Record<string, string> = {
  PAID: "bg-emerald-500/15 text-emerald-400 border border-emerald-500/30",
  OVERDUE: "bg-rose-500/15 text-rose-400 border border-rose-500/30",
  IN_PROGRESS: "bg-amber-500/15 text-amber-400 border border-amber-500/30",
  RESOLVED: "bg-emerald-500/15 text-emerald-400 border border-emerald-500/30",
};

export default function ClientPage() {
  const [chatOpen, setChatOpen] = useState(false);
  const [chatMessages, setChatMessages] = useState([
    { sender: "bot", text: "Bonjour SF ! Je suis l'assistant virtuel CallVerse. Comment puis-je vous aider aujourd'hui ?" }
  ]);
  const [chatInput, setChatInput] = useState("");

  const handleSendChat = (e: React.FormEvent) => {
    e.preventDefault();
    if (!chatInput.trim()) return;
    const userMsg = chatInput;
    setChatMessages(prev => [...prev, { sender: "user", text: userMsg }]);
    setChatInput("");
    setTimeout(() => {
      setChatMessages(prev => [
        ...prev,
        { sender: "bot", text: "Merci pour votre message. Un conseiller qualifié étudie votre dossier." }
      ]);
    }, 1000);
  };

  return (
    <AppShell role="Client" initials="SF" title="Mon Espace Client Bancaire">
      {/* Incident Outage Alert */}
      {mockIncident.active && (
        <div className="mb-4 flex shrink-0 items-center justify-between gap-3 rounded-2xl border border-amber-500/40 bg-amber-500/10 px-4 py-3 text-sm text-amber-300 backdrop-blur-xl shadow-lg">
          <div className="flex items-center gap-3">
            <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-amber-500/20 text-amber-400 shrink-0">
              <AlertTriangle size={18} />
            </div>
            <div>
              <span className="font-semibold text-white">Alerte Service :</span> Panne temporaire sur {mockIncident.channel}.
              <span className="ml-2 text-xs opacity-80">Rétablissement estimé à {mockIncident.eta}.</span>
            </div>
          </div>
          <span className="rounded-full bg-amber-500/20 px-3 py-1 text-xs font-mono font-medium text-amber-300 border border-amber-500/30">
            Incident Majeur
          </span>
        </div>
      )}

      {/* Grid Container */}
      <div className="grid flex-1 grid-cols-1 md:grid-cols-2 gap-4 overflow-y-auto pr-1">
        {/* Card 1: My Contract */}
        <div className="glass-card rounded-2xl p-5 border border-white/10 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between mb-4">
              <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider flex items-center gap-2">
                <CreditCard size={16} className="text-cyan-400" /> Mon Produit Souscrit
              </span>
              <span className="rounded-full bg-emerald-500/15 px-2.5 py-1 text-xs font-semibold text-emerald-400 border border-emerald-500/30 flex items-center gap-1">
                <CheckCircle2 size={12} /> {mockContract.status}
              </span>
            </div>
            <h3 className="font-display text-2xl font-bold text-white mb-4">{mockContract.plan}</h3>
            <div className="space-y-3 text-sm">
              <div className="flex justify-between py-2 border-b border-white/10">
                <span className="text-slate-400">Titulaire</span>
                <span className="font-medium text-white">Amadou Diallo</span>
              </div>
              <div className="flex justify-between py-2 border-b border-white/10">
                <span className="text-slate-400">Client depuis</span>
                <span className="font-mono font-medium text-cyan-300">{mockContract.since}</span>
              </div>
              <div className="flex justify-between py-2">
                <span className="text-slate-400">Prochaine Échéance</span>
                <span className="font-mono font-medium text-emerald-400">15 Septembre 2026</span>
              </div>
            </div>
          </div>
        </div>

        {/* Card 2: Invoices / Statements */}
        <div className="glass-card rounded-2xl p-5 border border-white/10 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between mb-4">
              <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider flex items-center gap-2">
                <Clock size={16} className="text-indigo-400" /> Mes Échéances & Relevés
              </span>
              <span className="text-xs text-slate-400 font-mono">3 Derniers mois</span>
            </div>
            <div className="space-y-2.5">
              {mockInvoices.map((inv) => (
                <div
                  key={inv.period}
                  className="flex items-center justify-between rounded-xl border border-white/10 bg-slate-900/60 p-3 text-sm transition-all hover:bg-white/5"
                >
                  <span className="font-medium text-white">{inv.period}</span>
                  <span className="font-mono font-bold text-slate-100">{inv.amount}</span>
                  <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${statusColor[inv.status]}`}>
                    {inv.status}
                  </span>
                  <button
                    onClick={() => alert(`Téléchargement du relevé ${inv.period}`)}
                    className="flex items-center gap-1 text-xs font-semibold text-cyan-400 hover:text-cyan-300 transition-colors"
                  >
                    <Download size={13} /> Relevé
                  </button>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Card 3: Support Tickets */}
        <div className="glass-card rounded-2xl p-5 border border-white/10 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between mb-4">
              <span className="text-xs font-semibold text-slate-400 uppercase tracking-wider flex items-center gap-2">
                <Shield size={16} className="text-purple-400" /> Mes Tickets de Support
              </span>
            </div>
            <div className="space-y-2.5">
              {mockTickets.map((t) => (
                <div
                  key={t.title}
                  className="flex items-center justify-between rounded-xl border border-white/10 bg-slate-900/60 p-3 text-sm"
                >
                  <span className="font-medium text-white">{t.title}</span>
                  <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${statusColor[t.status]}`}>
                    {t.status}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Card 4: Need Assistance CTA */}
        <div className="glass-card rounded-2xl p-5 border border-indigo-500/30 bg-gradient-to-br from-indigo-500/10 via-purple-500/10 to-cyan-500/10 flex flex-col justify-between">
          <div>
            <span className="inline-flex items-center gap-1 text-xs font-bold uppercase text-indigo-400 tracking-wider mb-2">
              <Sparkles size={14} /> Assistance 24/7
            </span>
            <h4 className="text-xl font-bold text-white mb-2">Une question sur vos paiements ?</h4>
            <p className="text-sm text-slate-300 font-light leading-relaxed">
              Discutez avec notre assistant intelligent ou demandez à être rappelé par un conseiller dédié.
            </p>
          </div>
          <button
            onClick={() => setChatOpen(true)}
            className="mt-4 flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-cyan-500 to-indigo-600 px-5 py-3 text-sm font-semibold text-white shadow-lg shadow-cyan-500/25 hover:brightness-110 active:scale-98 transition-all"
          >
            <MessageCircle size={16} /> Ouvrir le chat d&apos;assistance
          </button>
        </div>
      </div>

      {/* Floating Chat Modal */}
      {chatOpen && (
        <div className="fixed bottom-6 right-6 z-50 w-96 rounded-3xl border border-white/20 bg-slate-900/95 p-4 shadow-2xl backdrop-blur-2xl animate-in fade-in slide-in-from-bottom-5">
          <div className="flex items-center justify-between border-b border-white/10 pb-3 mb-3">
            <div className="flex items-center gap-2">
              <div className="flex h-8 w-8 items-center justify-center rounded-xl bg-cyan-500 text-white font-bold text-xs">
                AI
              </div>
              <div>
                <p className="text-xs font-bold text-white">Assistant CallVerse</p>
                <p className="text-[10px] text-emerald-400 flex items-center gap-1">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-ping" /> En ligne
                </p>
              </div>
            </div>
            <button
              onClick={() => setChatOpen(false)}
              className="rounded-lg p-1 text-slate-400 hover:bg-white/10 hover:text-white"
            >
              <X size={18} />
            </button>
          </div>

          {/* Messages Stream */}
          <div className="h-64 overflow-y-auto space-y-2.5 p-1 mb-3">
            {chatMessages.map((m, i) => (
              <div
                key={i}
                className={`max-w-[85%] rounded-2xl px-3.5 py-2 text-xs leading-relaxed ${
                  m.sender === "user"
                    ? "ml-auto bg-indigo-600 text-white"
                    : "mr-auto bg-white/10 text-slate-200 border border-white/10"
                }`}
              >
                {m.text}
              </div>
            ))}
          </div>

          {/* Input */}
          <form onSubmit={handleSendChat} className="flex gap-2">
            <input
              type="text"
              value={chatInput}
              onChange={(e) => setChatInput(e.target.value)}
              placeholder="Posez votre question..."
              className="glass-input flex-1 rounded-xl px-3 py-2 text-xs"
            />
            <button
              type="submit"
              className="rounded-xl bg-cyan-500 px-3 py-2 text-white hover:brightness-110 active:scale-95"
            >
              <Send size={14} />
            </button>
          </form>
        </div>
      )}
    </AppShell>
  );
}