"use client";

import { useEffect, useRef } from "react";

interface Particle {
  x: number;
  y: number;
  baseX: number;
  baseY: number;
  size: number;
  vx: number;
  vy: number;
  alpha: number;
  color: string;
}

export default function DynamicCanvas() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    let animationFrameId: number;
    let width = (canvas.width = window.innerWidth);
    let height = (canvas.height = window.innerHeight);

    let scrollY = window.scrollY;

    const handleScroll = () => {
      scrollY = window.scrollY;
    };

    const handleResize = () => {
      if (!canvas) return;
      width = canvas.width = window.innerWidth;
      height = canvas.height = window.innerHeight;
      initParticles();
    };

    window.addEventListener("scroll", handleScroll, { passive: true });
    window.addEventListener("resize", handleResize);

    const colors = ["#6366f1", "#06b6d4", "#8b5cf6", "#3b82f6", "#10b981"];
    let particles: Particle[] = [];

    const initParticles = () => {
      particles = [];
      const particleCount = Math.floor((width * height) / 14000);
      for (let i = 0; i < particleCount; i++) {
        const x = Math.random() * width;
        const y = Math.random() * height * 2; // extended for scrolling canvas depth
        particles.push({
          x,
          y,
          baseX: x,
          baseY: y,
          size: Math.random() * 2 + 1,
          vx: (Math.random() - 0.5) * 0.4,
          vy: (Math.random() - 0.5) * 0.4,
          alpha: Math.random() * 0.6 + 0.2,
          color: colors[Math.floor(Math.random() * colors.length)],
        });
      }
    };

    initParticles();

    const render = () => {
      ctx.clearRect(0, 0, width, height);

      // Radial background aura glow
      const radialGradient = ctx.createRadialGradient(
        width / 2,
        height / 2 - scrollY * 0.2,
        100,
        width / 2,
        height / 2,
        Math.max(width, height)
      );
      radialGradient.addColorStop(0, "rgba(99, 102, 241, 0.08)");
      radialGradient.addColorStop(0.5, "rgba(6, 182, 212, 0.04)");
      radialGradient.addColorStop(1, "rgba(8, 12, 20, 0.95)");
      ctx.fillStyle = radialGradient;
      ctx.fillRect(0, 0, width, height);

      // Draw grid line mesh that subtly reacts to scroll
      const gridSpacing = 80;
      const scrollOffset = (scrollY * 0.3) % gridSpacing;
      ctx.strokeStyle = "rgba(255, 255, 255, 0.02)";
      ctx.lineWidth = 1;

      for (let x = 0; x < width; x += gridSpacing) {
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, height);
        ctx.stroke();
      }

      for (let y = -gridSpacing + scrollOffset; y < height; y += gridSpacing) {
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(width, y);
        ctx.stroke();
      }

      // Update & Draw Particles with scroll parallax
      const parallaxFactor = 0.4;
      for (let i = 0; i < particles.length; i++) {
        const p = particles[i];

        // Move position slightly
        p.x += p.vx;
        p.y += p.vy;

        // Wrap around boundaries
        if (p.x < 0) p.x = width;
        if (p.x > width) p.x = 0;
        if (p.y < 0) p.y = height * 2;
        if (p.y > height * 2) p.y = 0;

        // Effective position on screen accounting for scroll parallax
        const screenY = (p.y - scrollY * parallaxFactor) % (height * 1.5);
        const actualY = screenY < 0 ? screenY + height * 1.5 : screenY;

        if (actualY > height + 50) continue;

        ctx.save();
        ctx.globalAlpha = p.alpha;
        ctx.fillStyle = p.color;
        ctx.shadowBlur = 12;
        ctx.shadowColor = p.color;
        ctx.beginPath();
        ctx.arc(p.x, actualY, p.size, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();

        // Connect close particles with dynamic glowing lines
        for (let j = i + 1; j < particles.length; j++) {
          const p2 = particles[j];
          const screenY2 = (p2.y - scrollY * parallaxFactor) % (height * 1.5);
          const actualY2 = screenY2 < 0 ? screenY2 + height * 1.5 : screenY2;

          const dx = p.x - p2.x;
          const dy = actualY - actualY2;
          const dist = Math.sqrt(dx * dx + dy * dy);

          if (dist < 110) {
            ctx.save();
            ctx.globalAlpha = (1 - dist / 110) * 0.15;
            ctx.strokeStyle = p.color;
            ctx.lineWidth = 0.8;
            ctx.beginPath();
            ctx.moveTo(p.x, actualY);
            ctx.lineTo(p2.x, actualY2);
            ctx.stroke();
            ctx.restore();
          }
        }
      }

      animationFrameId = requestAnimationFrame(render);
    };

    render();

    return () => {
      cancelAnimationFrame(animationFrameId);
      window.removeEventListener("scroll", handleScroll);
      window.removeEventListener("resize", handleResize);
    };
  }, []);

  return (
    <canvas
      ref={canvasRef}
      className="pointer-events-none fixed inset-0 z-0 h-full w-full"
    />
  );
}
