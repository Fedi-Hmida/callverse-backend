const { WebSocketServer } = require("ws");

const wss = new WebSocketServer({ port: 8081 });

wss.on("connection", (socket) => {
  console.log("Client connecte au mock WebSocket");

  const interval = setInterval(() => {
    const message = {
      topic: "/topic/queue/CREDIT",
      payload: {
        length: Math.floor(Math.random() * 10) + 20,
        avgWait: Math.floor(Math.random() * 60) + 60,
      },
      timestamp: new Date().toISOString(),
    };
    socket.send(JSON.stringify(message));
  }, 3000);

  socket.on("close", () => clearInterval(interval));
});

console.log("Mock WebSocket demarre sur ws://localhost:8081");