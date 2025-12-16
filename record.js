const wrtc = require("wrtc");
const WebSocket = require("ws");
const { spawn } = require("child_process");

const SIGNALING_URL = "wss://investments-coastal-digest-add.trycloudflare.com";

console.log("Connecting to:", SIGNALING_URL);

const ws = new WebSocket(SIGNALING_URL);

let pc;
let ffmpeg;
let started = false;

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

      // 🔴 Start ffmpeg ONLY after first frame (critical)
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

      // 🔴 Write raw frame data
      ffmpeg.stdin.write(Buffer.from(data));
    };

    // 🔴 FINALIZE MP4 PROPERLY
    event.track.onended = () => {
      console.log("Video track ended, finalizing mp4");

      if (ffmpeg) {
        ffmpeg.stdin.end();
        ffmpeg.kill("SIGINT");
      }

      sink.stop();
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
    } catch (e) {
      console.log("ICE error ignored");
    }
  }
});

// 🔴 SAFETY: finalize if process exits
process.on("SIGINT", () => {
  console.log("Process exiting, closing ffmpeg");
  if (ffmpeg) {
    ffmpeg.stdin.end();
    ffmpeg.kill("SIGINT");
  }
  process.exit();
});