"use client";

import { ReactNode, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { LayoutGrid, LogOut, ChevronDown, Sparkles, Home } from "lucide-react";
import DynamicCanvas from "./DynamicCanvas";

const roleAccent: Record<string, { color: string; label: string }> = {
  Client: { color: "#06B6D4", label: "Portail Client" },
  Conseiller: { color: "#6366F1", label: "Poste Conseiller" },
  Superviseur: { color: "#8B5CF6", label: "Centre Supervision" },
  "Analyste / Admin": { color: "#10B981", label: "Administration" },
};

export default function AppShell({
  role,
  initials,
  title,
  children,
}: {
  role: string;
  initials: string;
  title: string;
  children: ReactNode;
}) {
  const router = useRouter();
  const [profileOpen, setProfileOpen] = useState(false);
  const accentInfo = roleAccent[role] || { color: "#6366F1", label: role };
  const accent = accentInfo.color;

  const handleLogout = () => {
    // Clear cookies & localStorage
    document.cookie = "jwt=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT";
    document.cookie = "role=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT";
    if (typeof window !== "undefined") {
      localStorage.removeItem("jwt");
      localStorage.removeItem("role");
    }
    router.push("/");
  };

  return (
    <div className="relative flex h-screen w-screen overflow-hidden bg-[#07090e] text-slate-100 antialiased">
      {/* Background animated canvas */}
      <DynamicCanvas />

      <div className="relative z-10 flex h-full w-full p-3 md:p-4 gap-3">
        {/* Left Sidebar */}
        <aside className="glass-panel flex w-16 shrink-0 flex-col items-center justify-between rounded-2xl py-4 border border-white/10 shadow-2xl backdrop-blur-xl">
          <div className="flex flex-col items-center gap-4">
            <Link
              href="/"
              title="Retour à l'accueil"
              className="group relative flex h-10 w-10 items-center justify-center rounded-xl font-bold text-white transition-transform hover:scale-105 active:scale-95"
              style={{
                background: `linear-gradient(135deg, ${accent}, #06b6d4)`,
                boxShadow: `0 0 20px ${accent}66`,
              }}
            >
              <span className="font-mono text-sm tracking-tighter">CV</span>
              <div className="absolute -bottom-1 h-1 w-4 rounded-full" style={{ background: accent }} />
            </Link>

            <div className="h-px w-8 bg-white/10 my-1" />

            <Link
              href="/"
              title="Page d'accueil CallVerse"
              className="flex h-9 w-9 items-center justify-center rounded-xl text-slate-400 hover:bg-white/10 hover:text-white transition-colors"
            >
              <Home size={18} />
            </Link>

            <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-white/10 text-white shadow-inner">
              <LayoutGrid size={18} style={{ color: accent }} />
            </div>
          </div>

          <div className="flex flex-col items-center gap-3">
            <button
              onClick={handleLogout}
              title="Se déconnecter"
              className="flex h-9 w-9 items-center justify-center rounded-xl text-rose-400 hover:bg-rose-500/20 transition-all hover:scale-105"
            >
              <LogOut size={18} />
            </button>
          </div>
        </aside>

        {/* Main Content Area */}
        <div className="flex flex-1 flex-col overflow-hidden">
          {/* Header Bar */}
          <header className="glass-panel mb-3 flex shrink-0 items-center justify-between rounded-2xl px-5 py-3 border border-white/10 shadow-lg backdrop-blur-xl">
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-2">
                <span
                  className="h-2.5 w-2.5 rounded-full animate-ping"
                  style={{ background: accent }}
                />
                <h1 className="font-display text-base font-semibold tracking-tight text-white flex items-center gap-2">
                  {title}
                </h1>
              </div>
            </div>

            <div className="flex items-center gap-3">
              {/* Role pill badge */}
              <span
                className="inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold tracking-wide border shadow-sm"
                style={{
                  backgroundColor: `${accent}1A`,
                  borderColor: `${accent}40`,
                  color: accent,
                }}
              >
                <Sparkles size={13} />
                {role}
              </span>

              {/* User Dropdown */}
              <div className="relative">
                <button
                  onClick={() => setProfileOpen(!profileOpen)}
                  className="flex items-center gap-2 rounded-xl bg-white/5 p-1.5 pr-2.5 border border-white/10 hover:bg-white/10 transition-colors"
                >
                  <div
                    className="flex h-8 w-8 items-center justify-center rounded-lg font-bold text-xs text-white shadow"
                    style={{ background: `linear-gradient(135deg, ${accent}, #3b82f6)` }}
                  >
                    {initials}
                  </div>
                  <ChevronDown size={14} className="text-slate-400" />
                </button>

                {profileOpen && (
                  <div className="absolute right-0 top-12 z-50 w-52 rounded-2xl border border-white/15 bg-slate-900/95 p-2 shadow-2xl backdrop-blur-2xl animate-in fade-in slide-in-from-top-2">
                    <div className="px-3 py-2 border-b border-white/10">
                      <p className="text-xs font-semibold text-white">Session Active</p>
                      <p className="text-[11px] text-slate-400 truncate">{accentInfo.label}</p>
                    </div>
                    <button
                      onClick={handleLogout}
                      className="mt-1 flex w-full items-center gap-2 rounded-xl px-3 py-2 text-xs font-medium text-rose-400 hover:bg-rose-500/10 transition-colors"
                    >
                      <LogOut size={14} /> Se déconnecter
                    </button>
                  </div>
                )}
              </div>
            </div>
          </header>

          {/* Page Body Container */}
          <main className="glass-panel flex flex-1 flex-col overflow-hidden rounded-2xl p-4 border border-white/10 backdrop-blur-xl">
            {children}
          </main>
        </div>
      </div>
    </div>
  );
}