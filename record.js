const wrtc = require("wrtc");
const WebSocket = require("ws");
const { spawn } = require("child_process");
const jpeg = require("jpeg-js");

const SIGNALING_URL = "wss://investments-coastal-digest-add.trycloudflare.com";

const ws = new WebSocket(SIGNALING_URL);

let pc;
let ffmpeg;
let started = false;

ws.on("open", () => {
  pc = new wrtc.RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }]
  });

  pc.ontrack = (event) => {
    if (event.track.kind !== "video") return;

    const sink = new wrtc.nonstandard.RTCVideoSink(event.track);

    sink.onframe = ({ frame }) => {
      const { width, height } = frame;

      // Convert I420 → RGBA → JPEG
      const rgba = wrtc.nonstandard.i420ToRgba(frame);
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
      }

      ffmpeg.stdin.write(jpg.data);
    };

    event.track.onended = () => {
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
    await pc.setRemoteDescription(data);
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    ws.send(JSON.stringify(answer));
  }

  if (data.type === "ice-candidate") {
    try { await pc.addIceCandidate(data); } catch {}
  }
});