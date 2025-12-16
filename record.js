const wrtc = require("wrtc");
const WebSocket = require("ws");
const { spawn } = require("child_process");

const SIGNALING_URL = "wss://dayton-beer-consent-playing.trycloudflare.com";

const ws = new WebSocket(SIGNALING_URL);

let pc;
let ffmpeg;
let started = false;
let finalized = false;

function finalize(reason) {
  if (finalized) return;
  finalized = true;
  console.log("Finalizing because:", reason);

  if (ffmpeg) {
    try {
      ffmpeg.stdin.end();
      ffmpeg.kill("SIGINT");
    } catch {}
  }
}

ws.on("open", () => {
  pc = new wrtc.RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }]
  });

  pc.ontrack = (event) => {
    if (event.track.kind !== "video") return;

    const sink = new wrtc.nonstandard.RTCVideoSink(event.track);

    sink.onframe = ({ frame }) => {
      const width = frame.width;
      const height = frame.height;

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
          "-pix_fmt", "yuv420p",
          "-movflags", "+faststart",
          "recording.mp4"
        ]);

        ffmpeg.stderr.on("data", d =>
          console.log("[ffmpeg]", d.toString())
        );
      }

      if (!finalized && ffmpeg) {
        ffmpeg.stdin.write(Buffer.from(frame.data));
      }
    };

    event.track.onended = () => {
      console.log("Track ended");
      finalize("track ended");
      sink.stop();
    };
  };
});

ws.on("message", async (msg) => {
  const data = JSON.parse(msg);

  if (data.type === "offer") {
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
    try { await pc.addIceCandidate(data); } catch {}
  }
});

/* CANCEL / EXIT SAFE */
process.on("SIGINT", () => finalize("SIGINT"));
process.on("SIGTERM", () => finalize("SIGTERM"));
process.on("exit", () => finalize("exit"));
process.on("uncaughtException", err => {
  console.error(err);
  finalize("exception");
  process.exit(1);
});