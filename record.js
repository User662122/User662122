const wrtc = require("wrtc");
const WebSocket = require("ws");
const { spawn } = require("child_process");

const SIGNALING_URL = "wss://dayton-beer-consent-playing.trycloudflare.com";

const ws = new WebSocket(SIGNALING_URL);

let pc;
let ffmpeg;
let started = false;
let finalized = false;
let ffmpegExited = false;

function waitForFfmpegExit() {
  return new Promise((resolve) => {
    if (!ffmpeg || ffmpegExited) {
      resolve();
      return;
    }
    
    const checkInterval = setInterval(() => {
      if (ffmpegExited) {
        clearInterval(checkInterval);
        resolve();
      }
    }, 100);
    
    // Force resolve after 30 seconds
    setTimeout(() => {
      clearInterval(checkInterval);
      resolve();
    }, 30000);
  });
}

async function finalize(reason) {
  if (finalized) return;
  finalized = true;
  console.log("Finalizing because:", reason);

  if (ffmpeg && !ffmpegExited) {
    try {
      console.log("Closing FFmpeg stdin to trigger finalization...");
      ffmpeg.stdin.end();
      
      // Wait for FFmpeg to properly exit and write moov atom
      console.log("Waiting for FFmpeg to finalize MP4 (write moov atom)...");
      await waitForFfmpegExit();
      console.log("FFmpeg finalization complete");
    } catch (err) {
      console.error("Error finalizing FFmpeg:", err);
    }
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
        
        ffmpeg.on("exit", (code, signal) => {
          ffmpegExited = true;
          console.log(`FFmpeg exited with code ${code}, signal ${signal}`);
        });
        
        ffmpeg.on("error", (err) => {
          console.error("FFmpeg error:", err);
          ffmpegExited = true;
        });
      }

      if (!finalized && ffmpeg && !ffmpegExited) {
        try {
          ffmpeg.stdin.write(Buffer.from(frame.data));
        } catch (err) {
          console.error("Error writing frame:", err);
        }
      }
    };

    event.track.onended = async () => {
      console.log("Track ended");
      await finalize("track ended");
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

ws.on("close", async () => {
  console.log("WebSocket closed");
  await finalize("websocket closed");
  // Give time for file to be written
  await new Promise(r => setTimeout(r, 2000));
  process.exit(0);
});

ws.on("error", async (err) => {
  console.error("WebSocket error:", err);
  await finalize("websocket error");
  process.exit(1);
});

/* CANCEL / EXIT SAFE */
async function gracefulShutdown(reason) {
  await finalize(reason);
  // Give FFmpeg time to write the moov atom
  await new Promise(r => setTimeout(r, 2000));
  process.exit(0);
}

process.on("SIGINT", () => gracefulShutdown("SIGINT"));
process.on("SIGTERM", () => gracefulShutdown("SIGTERM"));

process.on("uncaughtException", async (err) => {
  console.error("Uncaught exception:", err);
  await finalize("exception");
  await new Promise(r => setTimeout(r, 2000));
  process.exit(1);
});

// Keep process alive and set recording duration
const RECORDING_DURATION_MS = 60000; // 60 seconds recording
console.log(`Recording will run for ${RECORDING_DURATION_MS/1000} seconds...`);

setTimeout(async () => {
  console.log("Recording duration reached, finalizing...");
  await finalize("duration reached");
  // Give FFmpeg time to finalize properly
  await new Promise(r => setTimeout(r, 5000));
  console.log("Recording complete, exiting...");
  process.exit(0);
}, RECORDING_DURATION_MS);
