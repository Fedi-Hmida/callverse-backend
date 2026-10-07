import { NextResponse } from "next/server";

export async function POST(request: Request) {
  const body = await request.json();

  // Mock : accepte n'importe quel email/mdp pour l'instant, renvoie un role selon l'email
  const role = body.email.includes("conseiller")
    ? "ADVISOR"
    : body.email.includes("superviseur")
    ? "SUPERVISOR"
    : body.email.includes("admin")
    ? "ADMIN"
    : "CUSTOMER";

  return NextResponse.json({
    token: "mock-jwt-token-12345",
    role: role,
  });
}