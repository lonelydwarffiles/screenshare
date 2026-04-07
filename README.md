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
│   └── package.json
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