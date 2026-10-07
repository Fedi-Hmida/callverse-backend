"use client";
import { useEffect, useState } from "react";
import { getStompClient } from "@/lib/stomp";

export function useQueueTopic(skill: string | null) {
  const [lastEvent, setLastEvent] = useState<any>(null);

  useEffect(() => {
    if (!skill) return;
    const client = getStompClient();
    let subId: string | undefined;

    const doSubscribe = () => {
      const sub = client.subscribe(`/topic/queue/${skill}`, (frame) => {
        setLastEvent(JSON.parse(frame.body));
      });
      subId = sub.id;
    };

    if (client.connected) doSubscribe();
    else client.onConnect = doSubscribe;

    return () => { if (subId) client.unsubscribe(subId); };
  }, [skill]);

  return lastEvent;
} 