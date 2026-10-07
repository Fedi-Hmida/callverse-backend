"use client";

import { useState } from "react";
import Link from "next/link";
import {
  Sparkles,
  ArrowRight,
  Zap,
  Users,
  Activity,
  MessageSquare,
  Bot,
  Layers,
  Send,
  Mail,
  Phone,
  MapPin,
  CheckCircle2,
  Headphones,
  Sliders,
} from "lucide-react";
import DynamicCanvas from "@/components/DynamicCanvas";

export default function Home() {
  const [contactSubmitted, setContactSubmitted] = useState(false);
  const [formData, setFormData] = useState({
    name: "",
    email: "",
    subject: "Démo entreprise",
    message: "",
  });

  const handleContactSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setContactSubmitted(true);
    setTimeout(() => {
      setContactSubmitted(false);
      setFormData({ name: "", email: "", subject: "Démo entreprise", message: "" });
    }, 4000);
  };

  return (
    <div className="relative min-h-screen bg-[#07090e] text-slate-100 selection:bg-indigo-500 selection:text-white">
      {/* Dynamic Background Canvas */}
      <DynamicCanvas />

      {/* Dynamic Ambient Blur Glows */}
      <div className="pointer-events-none fixed inset-0 overflow-hidden z-0">
        <div className="absolute top-[-10%] left-[-10%] h-[500px] w-[500px] rounded-full bg-indigo-600/20 blur-[130px] animate-pulse-glow" />
        <div className="absolute top-[40%] right-[-10%] h-[450px] w-[450px] rounded-full bg-cyan-500/15 blur-[140px] animate-pulse-glow" />
        <div className="absolute bottom-[-10%] left-[30%] h-[500px] w-[500px] rounded-full bg-purple-600/15 blur-[150px] animate-pulse-glow" />
      </div>

      {/* Sticky Glass Navbar */}
      <header className="sticky top-0 z-50 w-full backdrop-blur-xl bg-slate-950/70 border-b border-white/10 transition-all">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-6 py-4">
          <Link href="/" className="flex items-center gap-3 group">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 via-purple-500 to-cyan-400 font-bold text-white shadow-lg shadow-indigo-500/30 transition-transform group-hover:scale-105">
              CV
            </div>
            <div className="flex flex-col">
              <span className="font-display text-xl font-bold tracking-tight text-white flex items-center gap-1.5">
                CallVerse
                <span className="h-2 w-2 rounded-full bg-emerald-400 animate-ping" />
              </span>
              <span className="text-[10px] uppercase tracking-widest text-slate-400 font-medium">
                Jumeau Numérique Bancaire
              </span>
            </div>
          </Link>

          {/* Nav Links */}
          <nav className="hidden md:flex items-center gap-8 text-sm font-medium text-slate-300">
            <a href="#features" className="hover:text-cyan-400 transition-colors">
              Solutions
            </a>
            <a href="#digital-twin" className="hover:text-cyan-400 transition-colors">
              Jumeau Numérique
            </a>
            <a href="#ai-workforce" className="hover:text-cyan-400 transition-colors">
              IA & Workforce
            </a>
            <a href="#contact" className="hover:text-cyan-400 transition-colors">
              Contact
            </a>
          </nav>

          {/* Action CTAs: Login & Signup */}
          <div className="flex items-center gap-3">
            <Link
              href="/login"
              className="rounded-xl px-4 py-2 text-sm font-medium text-slate-200 border border-white/15 bg-white/5 hover:bg-white/10 hover:border-white/25 transition-all active:scale-95"
            >
              Se Connecter
            </Link>
            <Link
              href="/login"
              className="relative inline-flex items-center justify-center rounded-xl bg-gradient-to-r from-indigo-500 via-purple-500 to-cyan-500 px-4 py-2 text-sm font-semibold text-white shadow-lg shadow-indigo-500/25 transition-all hover:brightness-110 hover:shadow-indigo-500/40 active:scale-95"
            >
              <Sparkles size={15} className="mr-1.5" />
              S&apos;inscrire
            </Link>
          </div>
        </div>
      </header>

      {/* Hero Section */}
      <section className="relative z-10 mx-auto max-w-7xl px-6 pt-20 pb-16 text-center lg:pt-28">
        <div className="inline-flex items-center gap-2 rounded-full border border-indigo-500/30 bg-indigo-500/10 px-4 py-1.5 text-xs font-semibold text-indigo-300 backdrop-blur-md mb-8">
          <Sparkles size={14} className="text-cyan-400" />
          <span>Plateforme de Relation Client Bancaire Haute Performance</span>
        </div>

        <h1 className="mx-auto max-w-4xl font-display text-4xl font-extrabold tracking-tight sm:text-6xl lg:text-7xl">
          L&apos;avenir du centre de relation bancaire en{" "}
          <span className="bg-gradient-to-r from-indigo-400 via-cyan-400 to-emerald-400 bg-clip-text text-transparent">
            Temps Réel
          </span>
        </h1>

        <p className="mx-auto mt-6 max-w-2xl text-lg text-slate-400 sm:text-xl font-light">
          Simulez, pilotez et optimisez vos opérations bancaires grâce au jumeau numérique CallVerse. Assistance IA pour conseillers, supervision en direct et portail client immersif.
        </p>

        <div className="mt-10 flex flex-wrap items-center justify-center gap-4">
          <Link
            href="/login"
            className="flex items-center gap-2 rounded-2xl bg-gradient-to-r from-indigo-600 to-cyan-500 px-7 py-3.5 text-base font-semibold text-white shadow-xl shadow-indigo-500/30 hover:brightness-110 hover:shadow-indigo-500/50 transition-all active:scale-98"
          >
            Accéder à l&apos;Espace Démo
            <ArrowRight size={18} />
          </Link>
          <a
            href="#contact"
            className="flex items-center gap-2 rounded-2xl border border-white/15 bg-slate-900/60 px-7 py-3.5 text-base font-semibold text-slate-200 backdrop-blur-xl hover:bg-white/10 hover:border-white/25 transition-all"
          >
            Demander une Démo
          </a>
        </div>

        {/* Live Metrics Ticker Banner */}
        <div className="mt-16 grid grid-cols-2 gap-4 sm:grid-cols-4 lg:gap-6">
          <div className="glass-card rounded-2xl p-5 text-left">
            <div className="flex items-center justify-between text-slate-400 text-xs font-medium">
              <span>SLA Global</span>
              <Activity size={16} className="text-emerald-400" />
            </div>
            <p className="mt-2 font-display text-3xl font-bold text-white">98.4%</p>
            <p className="mt-1 text-xs text-emerald-400 font-medium">↑ +3.2% cette semaine</p>
          </div>

          <div className="glass-card rounded-2xl p-5 text-left">
            <div className="flex items-center justify-between text-slate-400 text-xs font-medium">
              <span>Temps d&apos;Attente Moyen</span>
              <Zap size={16} className="text-cyan-400" />
            </div>
            <p className="mt-2 font-display text-3xl font-bold text-white">1m 12s</p>
            <p className="mt-1 text-xs text-cyan-400 font-medium">Flux régulé en direct</p>
          </div>

          <div className="glass-card rounded-2xl p-5 text-left">
            <div className="flex items-center justify-between text-slate-400 text-xs font-medium">
              <span>Précision Suggestions IA</span>
              <Bot size={16} className="text-purple-400" />
            </div>
            <p className="mt-2 font-display text-3xl font-bold text-white">94.8%</p>
            <p className="mt-1 text-xs text-purple-400 font-medium">Base RAG enrichie</p>
          </div>

          <div className="glass-card rounded-2xl p-5 text-left">
            <div className="flex items-center justify-between text-slate-400 text-xs font-medium">
              <span>Satis. Client (CSAT)</span>
              <Users size={16} className="text-amber-400" />
            </div>
            <p className="mt-2 font-display text-3xl font-bold text-white">4.9 / 5</p>
            <p className="mt-1 text-xs text-amber-400 font-medium">+14k évaluations</p>
          </div>
        </div>
      </section>

      {/* Feature Showcase Grid */}
      <section id="features" className="relative z-10 mx-auto max-w-7xl px-6 py-20 border-t border-white/10">
        <div className="text-center mb-16">
          <h2 className="font-display text-3xl font-bold text-white sm:text-4xl">
            Quatre Workspaces Dédiés pour une Gestion à 360°
          </h2>
          <p className="mt-4 text-slate-400 max-w-2xl mx-auto font-light">
            Découvrez une suite applicative complète pensée pour l&apos;écosystème bancaire de nouvelle génération.
          </p>
        </div>

        <div className="grid gap-8 md:grid-cols-2 lg:grid-cols-4">
          {/* Card 1: Client */}
          <div className="glass-card rounded-3xl p-6 transition-all hover:-translate-y-1">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-cyan-500/10 text-cyan-400 border border-cyan-500/20 mb-6">
              <Headphones size={24} />
            </div>
            <h3 className="text-xl font-bold text-white mb-2">Espace Client</h3>
            <p className="text-slate-400 text-sm leading-relaxed mb-4">
              Aperçu des produits souscrits, suivi des échéances de prêt, relevés téléchargeables et canal de soutien 24/7.
            </p>
            <span className="text-xs font-semibold text-cyan-400 flex items-center gap-1">
              Rôle Client <ArrowRight size={14} />
            </span>
          </div>

          {/* Card 2: Advisor */}
          <div className="glass-card rounded-3xl p-6 transition-all hover:-translate-y-1">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-indigo-500/10 text-indigo-400 border border-indigo-500/20 mb-6">
              <MessageSquare size={24} />
            </div>
            <h3 className="text-xl font-bold text-white mb-2">Poste Conseiller</h3>
            <p className="text-slate-400 text-sm leading-relaxed mb-4">
              File d&apos;attente intelligente, chat en direct, suggestions RAG en temps réel et fiche 360 client avec risque de churn.
            </p>
            <span className="text-xs font-semibold text-indigo-400 flex items-center gap-1">
              Rôle Conseiller <ArrowRight size={14} />
            </span>
          </div>

          {/* Card 3: Supervisor */}
          <div className="glass-card rounded-3xl p-6 transition-all hover:-translate-y-1">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-purple-500/10 text-purple-400 border border-purple-500/20 mb-6">
              <Activity size={24} />
            </div>
            <h3 className="text-xl font-bold text-white mb-2">Centre Supervision</h3>
            <p className="text-slate-400 text-sm leading-relaxed mb-4">
              WebSockets en direct, monitoring des compétences, alertes SLA instantanées et recommandations d&apos;affectation IA.
            </p>
            <span className="text-xs font-semibold text-purple-400 flex items-center gap-1">
              Rôle Superviseur <ArrowRight size={14} />
            </span>
          </div>

          {/* Card 4: Admin */}
          <div className="glass-card rounded-3xl p-6 transition-all hover:-translate-y-1">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 mb-6">
              <Sliders size={24} />
            </div>
            <h3 className="text-xl font-bold text-white mb-2">Administration</h3>
            <p className="text-slate-400 text-sm leading-relaxed mb-4">
              Gestion de la base de connaissances, matrice des compétences conseillers, catalogue produits et règles métier.
            </p>
            <span className="text-xs font-semibold text-emerald-400 flex items-center gap-1">
              Rôle Admin <ArrowRight size={14} />
            </span>
          </div>
        </div>
      </section>

      {/* Digital Twin Showcase */}
      <section id="digital-twin" className="relative z-10 mx-auto max-w-7xl px-6 py-20 border-t border-white/10">
        <div className="grid lg:grid-cols-2 gap-12 items-center">
          <div>
            <div className="inline-flex items-center gap-2 rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3.5 py-1 text-xs font-medium text-cyan-300 mb-6">
              <Layers size={14} /> Architecture Jumeau Numérique
            </div>
            <h2 className="font-display text-3xl font-bold text-white sm:text-4xl">
              Une simulation ultra-réaliste en temps réel
            </h2>
            <p className="mt-4 text-slate-400 leading-relaxed font-light">
              CallVerse orchestre la communication entre clients et équipes bancaires grâce à un flux WebSocket bidirectionnel haut débit. Chaque événement de file d&apos;attente est modélisé et mis à jour instantanément.
            </p>

            <ul className="mt-8 space-y-4">
              <li className="flex items-center gap-3 text-slate-200">
                <CheckCircle2 size={18} className="text-emerald-400 shrink-0" />
                <span>Flux de données temps réel WebSockets multi-topics</span>
              </li>
              <li className="flex items-center gap-3 text-slate-200">
                <CheckCircle2 size={18} className="text-emerald-400 shrink-0" />
                <span>Moteur d&apos;attribution dynamique d&apos;advisors par compétence</span>
              </li>
              <li className="flex items-center gap-3 text-slate-200">
                <CheckCircle2 size={18} className="text-emerald-400 shrink-0" />
                <span>Recommandations prédictives de rebalancement d&apos;équipe</span>
              </li>
            </ul>
          </div>

          <div className="glass-panel rounded-3xl p-6 border border-white/15 shadow-2xl relative">
            <div className="flex items-center justify-between border-b border-white/10 pb-4 mb-4">
              <div className="flex items-center gap-2">
                <span className="h-3 w-3 rounded-full bg-rose-500" />
                <span className="h-3 w-3 rounded-full bg-amber-500" />
                <span className="h-3 w-3 rounded-full bg-emerald-500" />
              </div>
              <span className="text-xs font-mono text-slate-400">ws://localhost:8081</span>
            </div>

            <div className="space-y-3 font-mono text-xs text-slate-300">
              <div className="rounded-xl bg-slate-900/80 p-3 border border-indigo-500/30">
                <span className="text-indigo-400">[WS CONNECTED]</span> Monitoring file CREDIT...
              </div>
              <div className="rounded-xl bg-slate-900/80 p-3 border border-emerald-500/30">
                <span className="text-emerald-400">[SUGGESTION IA]</span> KB-Retard-Paiement-018 (Confiance 91%)
              </div>
              <div className="rounded-xl bg-slate-900/80 p-3 border border-purple-500/30">
                <span className="text-purple-400">[WORKFORCE AI]</span> Réaffectation recommandée: ACCOUNT → CREDIT
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Contact Section */}
      <section id="contact" className="relative z-10 mx-auto max-w-7xl px-6 py-20 border-t border-white/10">
        <div className="text-center mb-16">
          <h2 className="font-display text-3xl font-bold text-white sm:text-4xl">
            Contactez Notre Équipe
          </h2>
          <p className="mt-4 text-slate-400 max-w-2xl mx-auto font-light">
            Vous souhaitez intégrer CallVerse dans votre établissement bancaire ou planifier une présentation personnalisée ? Écrivez-nous.
          </p>
        </div>

        <div className="grid lg:grid-cols-3 gap-8">
          {/* Contact Info Cards */}
          <div className="space-y-4">
            <div className="glass-card rounded-2xl p-6 flex items-start gap-4">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-indigo-500/10 text-indigo-400 border border-indigo-500/20 shrink-0">
                <Mail size={20} />
              </div>
              <div>
                <h4 className="font-semibold text-white text-sm">Adresse Email</h4>
                <p className="text-slate-400 text-xs mt-1">contact@callverse-bank.com</p>
                <p className="text-slate-400 text-xs">support@callverse-bank.com</p>
              </div>
            </div>

            <div className="glass-card rounded-2xl p-6 flex items-start gap-4">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-cyan-500/10 text-cyan-400 border border-cyan-500/20 shrink-0">
                <Phone size={20} />
              </div>
              <div>
                <h4 className="font-semibold text-white text-sm">Ligne Directe</h4>
                <p className="text-slate-400 text-xs mt-1">+33 (0)1 89 45 20 00</p>
                <p className="text-slate-400 text-xs">Lun-Ven, 8h00 - 19h00</p>
              </div>
            </div>

            <div className="glass-card rounded-2xl p-6 flex items-start gap-4">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-purple-500/10 text-purple-400 border border-purple-500/20 shrink-0">
                <MapPin size={20} />
              </div>
              <div>
                <h4 className="font-semibold text-white text-sm">Siège Social</h4>
                <p className="text-slate-400 text-xs mt-1">75 Boulevard Haussmann</p>
                <p className="text-slate-400 text-xs">75008 Paris, France</p>
              </div>
            </div>
          </div>

          {/* Contact Form */}
          <div className="lg:col-span-2 glass-panel rounded-3xl p-8 border border-white/15 shadow-2xl">
            {contactSubmitted ? (
              <div className="flex flex-col items-center justify-center py-12 text-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-emerald-500/20 text-emerald-400 mb-4 animate-bounce">
                  <CheckCircle2 size={32} />
                </div>
                <h3 className="text-xl font-bold text-white">Message Envoyé avec Succès !</h3>
                <p className="mt-2 text-sm text-slate-300">
                  Merci de nous avoir contactés. Un conseiller CallVerse reviendra vers vous sous 24 heures.
                </p>
              </div>
            ) : (
              <form onSubmit={handleContactSubmit} className="space-y-6">
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-6">
                  <div>
                    <label className="block text-xs font-semibold text-slate-300 uppercase tracking-wider mb-2">
                      Nom complet
                    </label>
                    <input
                      type="text"
                      required
                      value={formData.name}
                      onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                      placeholder="Jean Dupont"
                      className="glass-input w-full rounded-xl px-4 py-3 text-sm"
                    />
                  </div>
                  <div>
                    <label className="block text-xs font-semibold text-slate-300 uppercase tracking-wider mb-2">
                      Email professionnel
                    </label>
                    <input
                      type="email"
                      required
                      value={formData.email}
                      onChange={(e) => setFormData({ ...formData, email: e.target.value })}
                      placeholder="j.dupont@banque.fr"
                      className="glass-input w-full rounded-xl px-4 py-3 text-sm"
                    />
                  </div>
                </div>

                <div>
                  <label className="block text-xs font-semibold text-slate-300 uppercase tracking-wider mb-2">
                    Objet de la demande
                  </label>
                  <select
                    value={formData.subject}
                    onChange={(e) => setFormData({ ...formData, subject: e.target.value })}
                    className="glass-input w-full rounded-xl px-4 py-3 text-sm bg-slate-900 text-white"
                  >
                    <option value="Démo entreprise">Demande de démonstration entreprise</option>
                    <option value="Intégration SI">Projet d&apos;intégration SI Bancaire</option>
                    <option value="Support technique">Support technique / Devis</option>
                    <option value="Autre">Autre question</option>
                  </select>
                </div>

                <div>
                  <label className="block text-xs font-semibold text-slate-300 uppercase tracking-wider mb-2">
                    Votre message
                  </label>
                  <textarea
                    required
                    rows={4}
                    value={formData.message}
                    onChange={(e) => setFormData({ ...formData, message: e.target.value })}
                    placeholder="Décrivez votre besoin ou votre projet..."
                    className="glass-input w-full rounded-xl px-4 py-3 text-sm"
                  />
                </div>

                <button
                  type="submit"
                  className="w-full flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-indigo-500 via-purple-500 to-cyan-500 py-3.5 text-sm font-semibold text-white shadow-lg shadow-indigo-500/25 hover:brightness-110 active:scale-98 transition-all"
                >
                  <Send size={16} /> Envoyer le message
                </button>
              </form>
            )}
          </div>
        </div>
      </section>

      {/* Footer */}
      <footer className="relative z-10 border-t border-white/10 bg-slate-950 py-10">
        <div className="mx-auto max-w-7xl px-6 flex flex-col md:flex-row items-center justify-between gap-6 text-xs text-slate-400">
          <div className="flex items-center gap-3">
            <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-indigo-500 font-bold text-white">
              CV
            </div>
            <span className="font-semibold text-slate-200">CallVerse Banking Simulator</span>
          </div>

          <div className="flex items-center gap-2 rounded-full border border-emerald-500/30 bg-emerald-500/10 px-3 py-1 text-emerald-400">
            <span className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse" />
            <span>Tous les systèmes opérationnels (ws://localhost:8081)</span>
          </div>

          <p>© {new Date().getFullYear()} CallVerse. Tous droits réservés.</p>
        </div>
      </footer>
    </div>
  );
}
