const wrtc = require("wrtc");
const WebSocket = require("ws");
const { spawn } = require("child_process");
const fs = require("fs");

// 🔴 YAHAN APNA CLOUDFARE URL DAALO
const SIGNALING_URL = "wss://contamination-models-beatles-philosophy.trycloudflare.com";

console.log("Connecting to:", SIGNALING_URL);

const ws = new WebSocket(SIGNALING_URL);

let pc;
let ffmpeg;
let sink;
let recording = false;
const filename = `recording_${Date.now()}.mp4`;

function cleanup() {
    console.log("Cleaning up...");
    if (sink) {
        sink.stop();
        sink = null;
    }
    if (ffmpeg) {
        ffmpeg.stdin.end(); // Properly close stdin
        setTimeout(() => {
            if (ffmpeg && !ffmpeg.killed) {
                ffmpeg.kill('SIGTERM');
            }
        }, 1000);
        ffmpeg = null;
    }
    if (pc) {
        pc.close();
        pc = null;
    }
    recording = false;
}

function startRecording() {
    console.log("Starting recording to:", filename);
    
    ffmpeg = spawn("ffmpeg", [
        "-y",                    // Overwrite output file
        "-f", "rawvideo",        // Input format
        "-pix_fmt", "yuv420p",   // Pixel format
        "-s", "720x1280",        // Resolution
        "-r", "30",              // Frame rate
        "-i", "-",               // Input from stdin
        "-c:v", "libx264",       // Video codec
        "-preset", "veryfast",   // Encoding preset
        "-crf", "23",            // Quality (lower = better)
        "-pix_fmt", "yuv420p",   // Output pixel format
        "-movflags", "+faststart", // Optimize for streaming
        filename
    ], {
        stdio: ['pipe', 'pipe', 'pipe']
    });

    // Log FFmpeg output
    ffmpeg.stderr.on('data', (data) => {
        console.log("FFmpeg:", data.toString());
    });

    ffmpeg.on('close', (code) => {
        console.log(`FFmpeg exited with code ${code}`);
        if (code === 0) {
            console.log(`✅ Recording saved to: ${filename}`);
            console.log(`File size: ${fs.statSync(filename).size} bytes`);
        } else {
            console.log(`❌ FFmpeg failed with code: ${code}`);
        }
    });

    ffmpeg.on('error', (err) => {
        console.error("FFmpeg error:", err);
    });

    recording = true;
}

ws.on("open", () => {
  console.log("WebSocket connected");

  pc = new wrtc.RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }]
  });

  pc.ontrack = (event) => {
    console.log("Track received:", event.track.kind);

    if (event.track.kind !== "video") return;

    if (sink) {
        sink.stop();
    }
    
    sink = new wrtc.nonstandard.RTCVideoSink(event.track);
    
    // Start recording when we get frames
    if (!recording) {
        startRecording();
    }

    let frameCount = 0;
    sink.onframe = ({ frame }) => {
        frameCount++;
        if (frameCount % 30 === 0) {
            console.log(`Frames received: ${frameCount}`);
        }
        
        if (ffmpeg && ffmpeg.stdin.writable) {
            try {
                ffmpeg.stdin.write(Buffer.from(frame.data));
            } catch (err) {
                console.error("Failed to write frame:", err.message);
            }
        }
    };
  };

  // Send client-connected message
  ws.send(JSON.stringify({ type: "client-connected" }));
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
    
    console.log("Answer sent");
  }

  if (data.type === "ice-candidate") {
    try {
      await pc.addIceCandidate(data);
      console.log("ICE candidate added");
    } catch (err) {
      console.log("ICE candidate error:", err.message);
    }
  }
  
  // Handle ping/pong for keep-alive
  if (data.type === "ping") {
    ws.send(JSON.stringify({ type: "pong", timestamp: data.timestamp }));
  }
});

ws.on("error", (err) => {
  console.error("WebSocket error:", err);
});

ws.on("close", () => {
  console.log("WebSocket closed");
  cleanup();
});

// Handle process termination
process.on('SIGINT', () => {
  console.log("\nStopping recording...");
  cleanup();
  setTimeout(() => process.exit(0), 2000);
});

process.on('exit', () => {
  cleanup();
});

console.log("Script started. Press Ctrl+C to stop recording and save file.");