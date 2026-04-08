# ScreenShare

A simple **end-to-end screen sharing app for Android** built with [WebRTC](https://webrtc.org/)
and a lightweight Node.js signaling server.

```
Broadcaster ──WebRTC──► Viewer
       ↕  signaling ↕
      WebSocket Server
```

---

## Architecture

| Component | Tech |
|-----------|------|
| Android app | Kotlin, WebRTC (`org.webrtc:google-webrtc`), OkHttp WebSocket |
| Signaling server | Node.js, `ws` |

### Key Android classes

| Class | Role |
|-------|------|
| `MainActivity` | Choose mode (broadcast / watch) and enter server URL |
| `BroadcastActivity` | Displays room ID and status while broadcasting |
| `ScreenShareService` | Foreground service: owns `MediaProjection` + WebRTC broadcaster |
| `ViewerActivity` | Enters room ID and renders the incoming video stream |
| `WebRTCClient` | Creates `PeerConnection`, handles screen capture via `ScreenCapturerAndroid` |
| `SignalingClient` | WebSocket client for SDP offer/answer and ICE candidate exchange |

---

## Quick Start

### 1. Start the signaling server

```bash
cd server
npm install
npm start          # listens on ws://localhost:8080
```

Set `PORT` to change the port:
```bash
PORT=9090 npm start
```

### 2. Build & install the Android app

Requirements: Android SDK (API 21+), Java 17+.

```bash
cd android
./gradlew assembleDebug
# Install on a connected device / emulator:
./gradlew installDebug
```

### 3. Configure the server URL

On the app's home screen enter the WebSocket URL of your signaling server:

| Scenario | URL |
|----------|-----|
| Android Emulator → host machine | `ws://10.0.2.2:8080` (default) |
| Physical device → local network server | `ws://192.168.x.x:8080` |
| Production | `wss://your-domain.com` |

### 4. Share a screen

1. Open the app on the **broadcaster** device.
2. Tap **Share My Screen** and grant the screen-capture permission.
3. A **4-digit room ID** is displayed on screen.

### 5. Watch the stream

**Option A — Browser viewer (no Android required)**

1. Open `http://<server-ip>:8080/viewer` in any modern browser.
2. Enter the room ID (and password if set) and click **Connect**.
3. The broadcaster's screen appears in the browser.

You can also deep-link directly to a session:

```
http://<server-ip>:8080/viewer#<roomId>
http://<server-ip>:8080/viewer#<roomId>?password=<pin>
```

**Option B — Android viewer app**

1. Open the app on the **viewer** device.
2. Tap **Watch a Stream**.
3. Enter the room ID and tap **Connect**.
4. The broadcaster's screen appears full-screen.

---

## Permissions

| Permission | Why |
|-----------|-----|
| `INTERNET` | WebRTC data + WebSocket signaling |
| `FOREGROUND_SERVICE` | Keep screen capture alive while app is backgrounded |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Required on Android 14+ |
| `POST_NOTIFICATIONS` | Show the foreground service notification |

---

## Project Structure

```
screenshare/
├── server/                        # Node.js signaling server
│   ├── server.js
│   ├── package.json
│   └── public/
│       └── viewer.html            # Browser viewer (served at GET /viewer)
└── android/                       # Android app
    ├── build.gradle
    ├── settings.gradle
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── java/com/screenshare/
        │   ├── MainActivity.kt
        │   ├── BroadcastActivity.kt
        │   ├── ViewerActivity.kt
        │   ├── ScreenShareService.kt
        │   ├── WebRTCClient.kt
        │   ├── SignalingClient.kt
        │   └── SimpleSdpObserver.kt
        └── res/
            ├── layout/
            └── values/
```

---

## Browser Viewer

The signaling server includes a built-in web viewer that works in any modern
browser (Chrome, Firefox, Safari, Edge) — no Android device needed.

**URL:** `http://<server-ip>:<PORT>/viewer`

### Deep-link formats

| Format | Example |
|--------|---------|
| Room ID in URL hash | `http://server:8080/viewer#1234` |
| Room + password in URL hash + query | `http://server:8080/viewer#1234?password=abc` |
| Room as query param | `http://server:8080/viewer?room=1234&password=abc` |

When a room ID is present in the URL the viewer connects automatically on load.

### Browser viewer controls

| Control | Description |
|---------|-------------|
| Emoji reactions (❤️ 🔥 👀 🥵 😈) | Sends `emoji` message to broadcaster |
| 📳 Buzz | Vibrates broadcaster's device |
| 🔒 Lock / 🔓 Unlock | Toggles `LockOverlayManager` on broadcaster |
| Chat | Send and receive text messages with broadcaster |
| Click/drag on video | Sends normalised touch coordinates → broadcaster injects gesture |
| 🔇 Unmute button | Audio starts muted for autoplay policy; click to unmute |

### Incoming broadcaster events

| Event | Browser behaviour |
|-------|------------------|
| `blackout` | Dims the video with a black overlay |
| `countdown` | Shows a full-screen countdown timer |
| `freeze` | Video frame naturally freezes (capture stopped) |
| `buzz` | Vibrates viewer's device via `navigator.vibrate` (where supported) |
| `chat` | Message appended to chat log |

---

## Signaling Protocol

All messages are JSON objects with a `type` field.

| Direction | Message | Fields |
|-----------|---------|--------|
| App → Server | `create` | `roomId` |
| App → Server | `join` | `roomId` |
| App → Server | `offer` | `sdp` |
| App → Server | `answer` | `sdp` |
| App → Server | `ice_candidate` | `candidate{sdpMid, sdpMLineIndex, candidate}` |
| Server → App | `created` | `roomId` |
| Server → App | `joined` | `roomId` |
| Server → App | `viewer_joined` | — |
| Server → App | `offer` | `sdp` |
| Server → App | `answer` | `sdp` |
| Server → App | `ice_candidate` | `candidate` |
| Server → App | `broadcast_ended` | — |
| Server → App | `error` | `message` |