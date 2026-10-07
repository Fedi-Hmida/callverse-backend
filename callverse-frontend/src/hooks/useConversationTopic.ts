"use client";
import { useEffect, useState } from "react";
import { getStompClient } from "@/lib/stomp";

export interface ConversationEvent {
  schemaVersion: 1;
  type: "MESSAGE_POSTED" | "STATUS_CHANGED";
  occurredAt: string;
  conversationId: string;
  status: string;
  messageId: number | null;
  sender: "CUSTOMER" | "ADVISOR" | "SYSTEM" | null;
  content: string | null;
  sentAt: string | null;
}

export function useConversationTopic(conversationId: string | null) {
  const [lastEvent, setLastEvent] = useState<ConversationEvent | null>(null);

  useEffect(() => {
    if (!conversationId) return;
    const client = getStompClient();
    let subId: string | undefined;

    const doSubscribe = () => {
      const sub = client.subscribe(`/topic/conversation/${conversationId}`, (frame) => {
        setLastEvent(JSON.parse(frame.body));
      });
      subId = sub.id;
    };

    if (client.connected) doSubscribe();
    else client.onConnect = doSubscribe;

    return () => { if (subId) client.unsubscribe(subId); };
  }, [conversationId]);

  return lastEvent;
}