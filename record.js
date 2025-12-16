const wrtc = require("wrtc");
const WebSocket = require("ws");
const { spawn } = require("child_process");

const SIGNALING_URL = "wss://alaska-websites-robinson-unless.trycloudflare.com";

console.log("Connecting to:", SIGNALING_URL);

const ws = new WebSocket(SIGNALING_URL);

let pc;
let ffmpeg;
let started = false;
let finalized = false;

function finalize(reason) {
  if (finalized) return;
  finalized = true;

  console.log("Finalizing video because:", reason);

  if (ffmpeg) {
    try {
      ffmpeg.stdin.end();
      ffmpeg.kill("SIGINT");
    } catch {}
  }
}

ws.on("open", () => {
  console.log("WebSocket connected");

  pc = new wrtc.RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }]
  });

  pc.ontrack = (event) => {
    console.log("Track received:", event.track.kind);
    if (event.track.kind !== "video") return;

    const sink = new wrtc.nonstandard.RTCVideoSink(event.track);

    sink.onframe = ({ frame }) => {
      const { width, height, data } = frame;

      if (!started) {
        started = true;
        console.log(`Starting ffmpeg ${width}x${height}`);

        ffmpeg = spawn("ffmpeg", [
          "-y",
          "-f", "rawvideo",
          "-pix_fmt", "yuv420p",
          "-s", `${width}x${height}`,
          "-r", "30",
          "-i", "-",
          "-c:v", "libx264",
          "-preset", "veryfast",
          "-profile:v", "baseline",
          "-level", "3.0",
          "-pix_fmt", "yuv420p",
          "-movflags", "+faststart",
          "recording.mp4"
        ]);

        ffmpeg.stderr.on("data", d => {
          console.log("[ffmpeg]", d.toString());
        });

        ffmpeg.on("close", code => {
          console.log("ffmpeg closed with code", code);
        });
      }

      if (ffmpeg && !finalized) {
        ffmpeg.stdin.write(Buffer.from(data));
      }
    };

    event.track.onended = () => {
      console.log("Video track ended");
      finalize("track ended");
      sink.stop();
    };
  };
});

ws.on("message", async (msg) => {
  const data = JSON.parse(msg);

  if (data.type === "offer") {
    await pc.setRemoteDescription({ type: "offer", sdp: data.sdp });
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    ws.send(JSON.stringify({ type: "answer", sdp: answer.sdp }));
  }

  if (data.type === "ice-candidate") {
    try {
      await pc.addIceCandidate(data);
    } catch {}
  }
});

/* 🔴 CANCEL / EXIT HANDLING (MOST IMPORTANT) */

// Linux / macOS / some Windows cases
process.on("SIGTERM", () => finalize("SIGTERM"));
process.on("SIGINT", () => finalize("SIGINT"));

// Windows hard-close / GitHub cancel
process.on("exit", () => finalize("process exit"));
process.on("uncaughtException", err => {
  console.error(err);
  finalize("uncaught exception");
  process.exit(1);
});