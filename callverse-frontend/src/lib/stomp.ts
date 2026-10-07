import { Client } from "@stomp/stompjs";

let client: Client | null = null;

function getToken() {
  return typeof window !== "undefined" ? sessionStorage.getItem("jwt") : null;
}

export function getStompClient(): Client {
  if (client) return client;

  client = new Client({
    brokerURL: process.env.NEXT_PUBLIC_WS_URL || "ws://localhost:8080/ws",
    connectHeaders: { Authorization: `Bearer ${getToken()}` },
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    beforeConnect: () => {
      if (client) client.connectHeaders = { Authorization: `Bearer ${getToken()}` };
    },
  });
  client.activate();
  return client;
}