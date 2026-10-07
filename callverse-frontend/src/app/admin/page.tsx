"use client";

import { useState } from "react";
import AppShell from "@/components/AppShell";
import { BookOpen, Package, Users, Sliders, Shield, Search, Plus, CheckCircle2, Clock } from "lucide-react";

const tabs = [
  { label: "Base de connaissances", icon: BookOpen },
  { label: "Catalogue produits", icon: Package },
  { label: "Conseillers", icon: Users },
  { label: "Règles et plafonds", icon: Sliders },
  { label: "Utilisateurs", icon: Shield },
];

const mockKb = [
  { title: "Procédure de blocage de carte", category: "CARD", published: true },
  { title: "Conditions de prêt personnel", category: "CREDIT", published: true },
  { title: "FAQ virement international", category: "ACCOUNT", published: false },
];

const mockPlans = [
  { code: "LOAN_PERS", name: "Prêt Personnel", price: "—" },
  { code: "LOAN_AUTO", name: "Crédit Auto", price: "—" },
  { code: "CARD_GOLD", name: "Carte Gold", price: "—" },
];

const mockAdvisors = [
  { name: "Amira B.", skills: "CREDIT, ACCOUNT" },
  { name: "Karim L.", skills: "CARD" },
];

const mockRules = [
  { name: "Plafond geste commercial standard", value: "50 €" },
  { name: "Seuil réaffectation file", value: "20 en attente" },
];

const mockUsers = [
  { name: "Karim L.", role: "SUPERVISOR" },
  { name: "Leïla H.", role: "ADMIN" },
];

export default function AdminPage() {
  const [activeTab, setActiveTab] = useState(0);
  const [searchQuery, setSearchQuery] = useState("");

  return (
    <AppShell role="Analyste / Admin" initials="LH" title="Back-Office d'Administration">
      {/* Header Controls Bar */}
      <div className="mb-4 flex flex-col md:flex-row md:items-center justify-between gap-3 shrink-0">
        {/* Scrollable Tabs */}
        <div className="flex gap-2 overflow-x-auto pb-1 md:pb-0">
          {tabs.map((tab, i) => {
            const Icon = tab.icon;
            return (
              <button
                key={tab.label}
                onClick={() => setActiveTab(i)}
                className={`flex items-center gap-2 whitespace-nowrap rounded-xl px-4 py-2.5 text-xs font-semibold transition-all ${
                  activeTab === i
                    ? "bg-gradient-to-r from-indigo-500 to-cyan-500 text-white shadow-lg shadow-indigo-500/25"
                    : "border border-white/10 bg-slate-900/60 text-slate-400 hover:bg-white/10 hover:text-white"
                }`}
              >
                <Icon size={15} />
                {tab.label}
              </button>
            );
          })}
        </div>

        {/* Search & Add Action */}
        <div className="flex items-center gap-2">
          <div className="relative">
            <Search size={14} className="absolute left-3 top-2.5 text-slate-400" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Rechercher..."
              className="glass-input rounded-xl pl-9 pr-3 py-1.5 text-xs w-44 focus:w-56 transition-all"
            />
          </div>
          <button
            onClick={() => alert("Ajout d'un élément")}
            className="flex items-center gap-1.5 rounded-xl bg-white/10 px-3 py-1.5 text-xs font-semibold text-white border border-white/15 hover:bg-white/20 transition-all active:scale-95 shrink-0"
          >
            <Plus size={14} /> Ajouter
          </button>
        </div>
      </div>

      {/* Main Glass Table Container */}
      <div className="flex-1 overflow-y-auto rounded-2xl border border-white/10 bg-slate-900/40 p-5 backdrop-blur-xl">
        {/* Tab 0: KB Articles */}
        {activeTab === 0 && (
          <table className="w-full text-xs text-left">
            <thead>
              <tr className="border-b border-white/10 text-slate-400 uppercase tracking-wider text-[11px]">
                <th className="pb-3 font-semibold">Titre de l&apos;Article</th>
                <th className="pb-3 font-semibold">Catégorie</th>
                <th className="pb-3 font-semibold">Statut</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {mockKb.map((a) => (
                <tr key={a.title} className="hover:bg-white/5 transition-colors">
                  <td className="py-3.5 font-medium text-white">{a.title}</td>
                  <td className="py-3.5 font-mono text-cyan-300">{a.category}</td>
                  <td className="py-3.5">
                    <span
                      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-[10px] font-semibold ${
                        a.published
                          ? "bg-emerald-500/15 text-emerald-400 border border-emerald-500/30"
                          : "bg-slate-500/15 text-slate-400 border border-slate-500/30"
                      }`}
                    >
                      {a.published ? (
                        <>
                          <CheckCircle2 size={11} /> Publié
                        </>
                      ) : (
                        <>
                          <Clock size={11} /> Brouillon
                        </>
                      )}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {/* Tab 1: Product Catalog */}
        {activeTab === 1 && (
          <table className="w-full text-xs text-left">
            <thead>
              <tr className="border-b border-white/10 text-slate-400 uppercase tracking-wider text-[11px]">
                <th className="pb-3 font-semibold">Code Produit</th>
                <th className="pb-3 font-semibold">Nom du Produit</th>
                <th className="pb-3 font-semibold">Tarification</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {mockPlans.map((p) => (
                <tr key={p.code} className="hover:bg-white/5 transition-colors">
                  <td className="py-3.5 font-mono text-indigo-400 font-bold">{p.code}</td>
                  <td className="py-3.5 font-medium text-white">{p.name}</td>
                  <td className="py-3.5 text-slate-400">{p.price}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {/* Tab 2: Advisors Skill Matrix */}
        {activeTab === 2 && (
          <table className="w-full text-xs text-left">
            <thead>
              <tr className="border-b border-white/10 text-slate-400 uppercase tracking-wider text-[11px]">
                <th className="pb-3 font-semibold">Conseiller</th>
                <th className="pb-3 font-semibold">Compétences Assignées</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {mockAdvisors.map((a) => (
                <tr key={a.name} className="hover:bg-white/5 transition-colors">
                  <td className="py-3.5 font-medium text-white">{a.name}</td>
                  <td className="py-3.5 font-mono text-cyan-300">{a.skills}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {/* Tab 3: Business Rules */}
        {activeTab === 3 && (
          <div className="space-y-3">
            {mockRules.map((r) => (
              <div
                key={r.name}
                className="flex items-center justify-between rounded-xl border border-white/10 bg-slate-900/60 p-4 text-xs"
              >
                <span className="font-medium text-white">{r.name}</span>
                <span className="font-mono font-bold text-cyan-400 bg-cyan-500/10 border border-cyan-500/20 px-3 py-1 rounded-lg">
                  {r.value}
                </span>
              </div>
            ))}
          </div>
        )}

        {/* Tab 4: User Roles */}
        {activeTab === 4 && (
          <table className="w-full text-xs text-left">
            <thead>
              <tr className="border-b border-white/10 text-slate-400 uppercase tracking-wider text-[11px]">
                <th className="pb-3 font-semibold">Nom de l&apos;Utilisateur</th>
                <th className="pb-3 font-semibold">Rôle attribué</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-white/5">
              {mockUsers.map((u) => (
                <tr key={u.name} className="hover:bg-white/5 transition-colors">
                  <td className="py-3.5 font-medium text-white">{u.name}</td>
                  <td className="py-3.5 font-mono text-purple-400 font-bold">{u.role}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </AppShell>
  );
}