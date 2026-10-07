"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { apiFetch } from "@/lib/api";
import { Lock, Mail, ArrowRight, Shield, Sparkles, ArrowLeft, User, Headphones, Activity, Settings } from "lucide-react";
import DynamicCanvas from "@/components/DynamicCanvas";

export default function LoginPage() {
  const [isSignup, setIsSignup] = useState(false);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  async function performLogin(targetEmail: string, targetPass: string) {
  setError(null);
  setLoading(true);
  try {
    const data = await apiFetch("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email: targetEmail, password: targetPass }),
    });
    sessionStorage.setItem("jwt", data.token);       // token : sessionStorage uniquement
    document.cookie = `role=${data.role}; path=/`;    // role : cookie, lu par le middleware

    const roleHome: Record<string, string> = {
      CUSTOMER: "/client",
      ADVISOR: "/advisor",
      SUPERVISOR: "/supervisor",
      ADMIN: "/admin",
    };
    router.push(roleHome[data.role] || "/");
  } catch {
    setError("Identifiants invalides. Veuillez réessayer.");
  } finally {
    setLoading(false);
  }
}

async function handleSubmit(e: React.FormEvent) {
  e.preventDefault();
  await performLogin(email, password);
}

  const demoAccounts = [
    {
      role: "Client",
      email: "client@banque.com",
      icon: User,
      color: "from-cyan-500 to-blue-500",
      desc: "Portail usager & prêts",
    },
    {
      role: "Conseiller",
      email: "conseiller@banque.com",
      icon: Headphones,
      color: "from-indigo-500 to-purple-500",
      desc: "Workstation & Suggestions IA",
    },
    {
      role: "Superviseur",
      email: "superviseur@banque.com",
      icon: Activity,
      color: "from-purple-500 to-pink-500",
      desc: "Supervision & WebSockets",
    },
    {
      role: "Admin",
      email: "admin@banque.com",
      icon: Settings,
      color: "from-emerald-500 to-teal-500",
      desc: "Back-office & Règles",
    },
  ];

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-[#07090e] px-4 py-10">
      {/* Canvas dynamic background */}
      <DynamicCanvas />

      {/* Ambient glows */}
      <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -left-32 top-[-10%] h-[450px] w-[450px] rounded-full bg-indigo-600/25 blur-[120px] motion-safe:animate-[drift1_16s_ease-in-out_infinite]" />
        <div className="absolute right-[-10%] top-1/3 h-[400px] w-[400px] rounded-full bg-cyan-500/20 blur-[120px] motion-safe:animate-[drift2_20s_ease-in-out_infinite]" />
        <div className="absolute bottom-[-15%] left-1/3 h-[500px] w-[500px] rounded-full bg-purple-600/15 blur-[130px] motion-safe:animate-[drift3_24s_ease-in-out_infinite]" />
      </div>

      <div className="relative z-10 w-full max-w-4xl space-y-6">
        {/* Top Navbar Back Link */}
        <div className="flex items-center justify-between">
          <Link
            href="/"
            className="flex items-center gap-2 text-xs font-semibold text-slate-400 hover:text-white transition-colors bg-white/5 border border-white/10 rounded-xl px-3 py-2 backdrop-blur-md"
          >
            <ArrowLeft size={15} />
            Retour à l&apos;accueil
          </Link>
          <div className="flex items-center gap-2">
            <span className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse" />
            <span className="text-xs text-slate-400">Environnement Démo Sécurisé</span>
          </div>
        </div>

        <div className="grid md:grid-cols-12 gap-6 items-stretch">
          {/* Main Auth Card (7 cols) */}
          <div className="md:col-span-7 glass-panel rounded-3xl p-8 border border-white/15 shadow-2xl backdrop-blur-2xl flex flex-col justify-between">
            <div>
              {/* Header */}
              <div className="mb-6 flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 to-cyan-400 font-bold text-white shadow-lg">
                    CV
                  </div>
                  <div>
                    <h2 className="font-display text-lg font-bold text-white">CallVerse</h2>
                    <p className="text-xs text-slate-400">Plateforme Bancaire Multi-Rôles</p>
                  </div>
                </div>
              </div>

              {/* Login / Signup Tabs */}
              <div className="mb-6 flex rounded-xl bg-slate-900/80 p-1 border border-white/10">
                <button
                  onClick={() => setIsSignup(false)}
                  className={`flex-1 rounded-lg py-2 text-xs font-semibold transition-all ${
                    !isSignup
                      ? "bg-indigo-600 text-white shadow-md"
                      : "text-slate-400 hover:text-white"
                  }`}
                >
                  Connexion
                </button>
                <button
                  onClick={() => setIsSignup(true)}
                  className={`flex-1 rounded-lg py-2 text-xs font-semibold transition-all ${
                    isSignup
                      ? "bg-indigo-600 text-white shadow-md"
                      : "text-slate-400 hover:text-white"
                  }`}
                >
                  Créer un compte
                </button>
              </div>

              <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                  <label className="mb-1.5 block text-xs font-semibold text-slate-300">
                    Adresse Email
                  </label>
                  <div className="relative">
                    <Mail size={16} className="absolute left-3.5 top-3 text-slate-400" />
                    <input
                      type="email"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      required
                      placeholder="vous@banque.com"
                      className="glass-input w-full rounded-xl pl-10 pr-3.5 py-2.5 text-sm"
                    />
                  </div>
                </div>

                <div>
                  <label className="mb-1.5 block text-xs font-semibold text-slate-300">
                    Mot de passe
                  </label>
                  <div className="relative">
                    <Lock size={16} className="absolute left-3.5 top-3 text-slate-400" />
                    <input
                      type="password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      required
                      placeholder="••••••••"
                      className="glass-input w-full rounded-xl pl-10 pr-3.5 py-2.5 text-sm"
                    />
                  </div>
                </div>

                {error && (
                  <p className="rounded-xl border border-rose-500/30 bg-rose-500/10 px-3.5 py-2 text-xs font-medium text-rose-300">
                    {error}
                  </p>
                )}

                <button
                  type="submit"
                  disabled={loading}
                  className="w-full flex items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-indigo-500 via-purple-500 to-cyan-500 py-3 text-sm font-semibold text-white shadow-lg shadow-indigo-500/25 hover:brightness-110 active:scale-98 transition-all disabled:opacity-50"
                >
                  {loading ? (
                    "Connexion en cours..."
                  ) : (
                    <>
                      {isSignup ? "Créer mon espace" : "Se connecter"}
                      <ArrowRight size={16} />
                    </>
                  )}
                </button>
              </form>
            </div>

            <p className="mt-6 text-center text-[11px] text-slate-400 font-light">
              Données chiffrées · Accès sécurisé SSL/TLS 256-bit
            </p>
          </div>

          {/* Quick Demo Presets Side Panel (5 cols) */}
          <div className="md:col-span-5 glass-panel rounded-3xl p-6 border border-white/15 shadow-2xl backdrop-blur-2xl flex flex-col justify-between">
            <div>
              <div className="flex items-center gap-2 text-indigo-400 mb-2">
                <Sparkles size={16} />
                <span className="text-xs font-bold uppercase tracking-wider">Accès Démo 1-Clic</span>
              </div>
              <h3 className="text-base font-bold text-white mb-1">Tester un Rôle Spécifique</h3>
              <p className="text-xs text-slate-400 mb-4 font-light leading-relaxed">
                Cliquez sur n&apos;importe quel rôle pour charger immédiatement une session complète sans saisir de mot de passe.
              </p>

              <div className="space-y-2.5">
                {demoAccounts.map((acc) => {
                  const Icon = acc.icon;
                  return (
                    <button
                      key={acc.role}
                      type="button"
                      onClick={() => {
                        setEmail(acc.email);
                        setPassword("demo1234");
                        performLogin(acc.email, "demo1234");
                      }}
                      className="w-full flex items-center justify-between p-3 rounded-2xl border border-white/10 bg-white/5 hover:bg-white/10 hover:border-indigo-500/40 transition-all text-left group"
                    >
                      <div className="flex items-center gap-3">
                        <div className={`flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-br ${acc.color} text-white shadow-md`}>
                          <Icon size={16} />
                        </div>
                        <div>
                          <p className="text-xs font-bold text-white group-hover:text-cyan-300 transition-colors">
                            {acc.role}
                          </p>
                          <p className="text-[10px] text-slate-400">{acc.desc}</p>
                        </div>
                      </div>
                      <ArrowRight size={14} className="text-slate-400 group-hover:text-white group-hover:translate-x-1 transition-all" />
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="mt-4 rounded-xl bg-indigo-500/10 border border-indigo-500/20 p-3 text-[11px] text-indigo-300 flex items-center gap-2">
              <Shield size={14} className="shrink-0" />
              <span>Session de démonstration réinitialisable à tout moment.</span>
            </div>
          </div>
        </div>
      </div>
    </main>
  );
}