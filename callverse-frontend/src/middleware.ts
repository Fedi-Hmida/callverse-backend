import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

const roleHome: Record<string, string> = {
  CUSTOMER: "/client",
  ADVISOR: "/advisor",
  SUPERVISOR: "/supervisor",
  ADMIN: "/admin",
};

export function middleware(request: NextRequest) {
  const role = request.cookies.get("role")?.value;
  const { pathname } = request.nextUrl;

  // Public routes: Home landing page & login
  if (pathname === "/" || pathname === "/login") {
    return NextResponse.next();
  }

  // Guard protected routes if no role cookie is present
  if (!role) {
    return NextResponse.redirect(new URL("/login", request.url));
  }

  const homePage = roleHome[role];

  // Restrict access so roles can only access their authorized workspace route
  if (homePage && !pathname.startsWith(homePage)) {
    return NextResponse.redirect(new URL(homePage, request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|api).*)"],
};