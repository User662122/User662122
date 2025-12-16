const wrtc = require("wrtc");
const WebSocket = require("ws");
const { spawn } = require("child_process");
const jpeg = require("jpeg-js");

const SIGNALING_URL = "wss://dayton-beer-consent-playing.trycloudflare.com";

const ws = new WebSocket(SIGNALING_URL);

let pc;
let ffmpeg;
let started = false;
let finalized = false;

function finalize(reason) {
  if (finalized) return;
  finalized = true;

  console.log("Finalizing recording because:", reason);

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

      // ✅ FIX-1: Correct i420ToRgba usage
      const rgba = wrtc.nonstandard.i420ToRgba({
        width,
        height,
        data: frame.data
      });

      const jpg = jpeg.encode(
        { data: rgba, width, height },
        80
      );

      if (!started) {
        started = true;

        ffmpeg = spawn("ffmpeg", [
          "-y",
          "-f", "image2pipe",
          "-vcodec", "mjpeg",
          "-r", "30",
          "-i", "-",
          "-c:v", "libx264",
          "-pix_fmt", "yuv420p",
          "-movflags", "+faststart",
          "recording.mp4"
        ]);

        ffmpeg.stderr.on("data", d =>
          console.log("[ffmpeg]", d.toString())
        );

        ffmpeg.on("close", code =>
          console.log("ffmpeg closed with code", code)
        );
      }

      if (ffmpeg && !finalized) {
        ffmpeg.stdin.write(jpg.data);
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
    try {
      await pc.addIceCandidate(data);
    } catch {}
  }
});

/* ✅ FIX-2: Cancel / crash / exit safe finalize */

process.on("SIGINT", () => finalize("SIGINT"));
process.on("SIGTERM", () => finalize("SIGTERM"));
process.on("exit", () => finalize("process exit"));
process.on("uncaughtException", err => {
  console.error(err);
  finalize("uncaught exception");
  process.exit(1);
});