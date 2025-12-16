const wrtc = require("wrtc");
const WebSocket = require("ws");
const { spawn } = require("child_process");

// 🔴 YAHAN APNA CLOUDFARE URL DAALO
const SIGNALING_URL = "wss://biography-flu-plot-biographies.trycloudflare.com";

console.log("Connecting to:", SIGNALING_URL);

const ws = new WebSocket(SIGNALING_URL);

let pc;
let ffmpeg;

ws.on("open", () => {
  console.log("WebSocket connected");

  pc = new wrtc.RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }]
  });

  pc.ontrack = (event) => {
    console.log("Track received:", event.track.kind);

    if (event.track.kind !== "video") return;

    const sink = new wrtc.nonstandard.RTCVideoSink(event.track);

    ffmpeg = spawn("ffmpeg", [
      "-y",
      "-f", "rawvideo",
      "-pix_fmt", "yuv420p",
      "-s", "720x1280",
      "-r", "30",
      "-i", "-",
      "-c:v", "libx264",
      "-preset", "veryfast",
      "recording.mp4"
    ]);

    sink.onframe = ({ frame }) => {
      ffmpeg.stdin.write(Buffer.from(frame.data));
    };
  };
});

ws.on("message", async (msg) => {
  const data = JSON.parse(msg);

  if (data.type === "offer") {
    console.log("Offer received");

    await pc.setRemoteDescription({
      type: "offer",
      sdp: data.sdp
    });

    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);

    ws.send(JSON.stringify({
      type: "answer",
      sdp: answer.sdp
    }));
  }

  if (data.type === "ice-candidate") {
    try {
      await pc.addIceCandidate(data);
    } catch {}
  }
});
