const wrtc = require("wrtc");
const WebSocket = require("ws");
const { spawn } = require("child_process");
const fs = require("fs");

// 🔴 YAHAN APNA CLOUDFARE URL DAALO
const SIGNALING_URL = "wss://bottom-define-nursery-commitments.trycloudflare.com";

console.log("Connecting to:", SIGNALING_URL);

const ws = new WebSocket(SIGNALING_URL);

let pc;
let ffmpeg;
let sink;
let recording = false;
let framesWritten = 0;
const filename = `recording_${Date.now()}.mp4`;

// Function to properly stop FFmpeg and save file
function stopFFmpeg() {
    return new Promise((resolve) => {
        if (!ffmpeg || ffmpeg.killed) {
            resolve();
            return;
        }
        
        console.log("Stopping FFmpeg gracefully...");
        
        // End stdin to signal EOF
        if (ffmpeg.stdin.writable) {
            ffmpeg.stdin.end();
        }
        
        // Wait for FFmpeg to finish
        ffmpeg.on('close', (code) => {
            console.log(`FFmpeg exited with code ${code}`);
            console.log(`✅ Recording saved to: ${filename}`);
            console.log(`Frames written: ${framesWritten}`);
            
            // Verify file exists and has content
            if (fs.existsSync(filename)) {
                const stats = fs.statSync(filename);
                console.log(`File size: ${(stats.size / 1024 / 1024).toFixed(2)} MB`);
                if (stats.size === 0) {
                    console.log("⚠️  Warning: File is empty!");
                }
            } else {
                console.log("❌ File was not created!");
            }
            
            resolve();
        });
        
        // Kill after timeout if it doesn't exit
        setTimeout(() => {
            if (ffmpeg && !ffmpeg.killed) {
                console.log("Force killing FFmpeg...");
                ffmpeg.kill('SIGKILL');
                resolve();
            }
        }, 3000);
    });
}

async function cleanup() {
    console.log("\n🔄 Cleaning up resources...");
    
    // Stop sink first
    if (sink) {
        console.log("Stopping video sink...");
        sink.stop();
        sink = null;
    }
    
    // Stop FFmpeg properly
    await stopFFmpeg();
    
    // Close WebRTC connection
    if (pc) {
        console.log("Closing WebRTC connection...");
        pc.close();
        pc = null;
    }
    
    // Close WebSocket
    if (ws && ws.readyState === WebSocket.OPEN) {
        console.log("Closing WebSocket...");
        ws.close();
    }
    
    recording = false;
    console.log("✅ Cleanup complete!");
}

function startRecording() {
    console.log("🎬 Starting recording to:", filename);
    
    ffmpeg = spawn("ffmpeg", [
        "-y",                    // Overwrite output file
        "-f", "rawvideo",        // Input format
        "-pixel_format", "yuv420p", // Pixel format
        "-video_size", "720x1280", // Resolution
        "-framerate", "30",      // Frame rate
        "-i", "-",               // Input from stdin
        "-c:v", "libx264",       // Video codec
        "-preset", "ultrafast",  // Fast encoding
        "-crf", "28",            // Quality
        "-movflags", "frag_keyframe+empty_moov+faststart", // Better for streaming
        "-f", "mp4",             // Force MP4 format
        filename
    ], {
        stdio: ['pipe', 'ignore', 'pipe'] // Redirect stderr to pipe
    });

    // Monitor FFmpeg output
    ffmpeg.stderr.on('data', (data) => {
        const output = data.toString();
        // Show important messages only
        if (output.includes('frame=') || output.includes('size=') || output.includes('time=')) {
            process.stdout.write(`\rFFmpeg: ${output.trim()}`);
        }
    });

    ffmpeg.on('error', (err) => {
        console.error("\n❌ FFmpeg error:", err.message);
    });

    recording = true;
    console.log("⏺️  Recording started. Press Ctrl+C to stop and save.");
}

ws.on("open", () => {
  console.log("✅ WebSocket connected");

  pc = new wrtc.RTCPeerConnection({
    iceServers: [{ urls: "stun:stun.l.google.com:19302" }]
  });

  pc.ontrack = (event) => {
    console.log(`🎥 Track received: ${event.track.kind} (${event.track.id})`);

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
        framesWritten++;
        
        // Show progress every 30 frames (~1 second)
        if (frameCount % 30 === 0) {
            process.stdout.write(`\r📊 Frames received: ${frameCount} (${framesWritten} written)`);
        }
        
        if (ffmpeg && ffmpeg.stdin.writable) {
            try {
                // Write frame data to FFmpeg
                const written = ffmpeg.stdin.write(Buffer.from(frame.data));
                if (!written) {
                    console.log("\n⚠️  FFmpeg buffer full, frames may be dropped");
                }
            } catch (err) {
                console.error("\n❌ Failed to write frame:", err.message);
            }
        }
    };
    
    console.log("🚀 Video sink initialized. Waiting for frames...");
  };

  // Send client-connected message
  ws.send(JSON.stringify({ type: "client-connected" }));
});

ws.on("message", async (msg) => {
  const data = JSON.parse(msg);

  if (data.type === "offer") {
    console.log("📨 Offer received");

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
    
    console.log("📤 Answer sent");
  }

  if (data.type === "ice-candidate") {
    try {
      await pc.addIceCandidate(data);
      console.log("❄️  ICE candidate added");
    } catch (err) {
      console.log("⚠️  ICE candidate error:", err.message);
    }
  }
  
  // Handle ping/pong for keep-alive
  if (data.type === "ping") {
    ws.send(JSON.stringify({ type: "pong", timestamp: data.timestamp }));
  }
});

ws.on("error", (err) => {
  console.error("❌ WebSocket error:", err.message);
});

ws.on("close", async () => {
  console.log("\n🔌 WebSocket closed");
  await cleanup();
});

// Handle Ctrl+C gracefully
process.on('SIGINT', async () => {
  console.log("\n\n🛑 Ctrl+C pressed. Stopping gracefully...");
  await cleanup();
  process.exit(0);
});

// Handle other termination signals
process.on('SIGTERM', async () => {
  console.log("\n🔚 SIGTERM received");
  await cleanup();
  process.exit(0);
});

// Handle process exit
process.on('exit', (code) => {
  console.log(`\n👋 Process exiting with code ${code}`);
});

console.log("=".repeat(50));
console.log("📱 Android Screen Stream Recorder");
console.log("=".repeat(50));
console.log("Instructions:");
console.log("1. Make sure Android app is streaming");
console.log("2. This script will automatically connect");
console.log("3. Video will be saved to: recording_TIMESTAMP.mp4");
console.log("4. Press Ctrl+C to stop and save the recording");
console.log("=".repeat(50));