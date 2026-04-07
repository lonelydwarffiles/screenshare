/**
 * WebSocket signaling server for the ScreenShare Android app.
 *
 * Rooms: one broadcaster streams to one or more viewers.
 * Messages are plain JSON objects with a "type" field.
 *
 * Room options (set at create time):
 *   password   – optional PIN; viewers must supply the same value to join
 *   maxViewers – 0 means unlimited; positive integer caps simultaneous viewers
 *   useKnock   – if true, viewers must knock and broadcaster must accept them
 *
 * Extra server → client messages beyond the baseline:
 *   viewer_count   { count }                   – broadcast count to broadcaster
 *   viewer_knock   { viewerId, displayName }   – broadcaster sees a knock
 *   knock_accepted {}                           – viewer was accepted
 *   knock_rejected {}                           – viewer was rejected
 *   rejected       { reason }                  – viewer was turned away
 *
 * HTTP endpoints:
 *   GET /rooms   – returns JSON array of discoverable rooms
 *   GET /        – health check
 *
 * Room expiry: rooms auto-close after ROOM_IDLE_MS of no viewer activity
 * when there are no viewers connected.
 */

'use strict';

const http = require('http');
const { randomUUID } = require('crypto');
const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
/** Rooms with no viewers are deleted after this many ms of broadcaster-only idle. */
const ROOM_IDLE_MS = 30 * 60 * 1000; // 30 minutes

// ---------------------------------------------------------------------------
// HTTP server (serves health check + room discovery)
// ---------------------------------------------------------------------------

const server = http.createServer((req, res) => {
    const url = new URL(req.url, `http://localhost:${PORT}`);

    if (url.pathname === '/rooms' && req.method === 'GET') {
        const list = [];
        rooms.forEach((room, roomId) => {
            if (!room.hidden) {
                list.push({
                    roomId,
                    viewerCount: room.viewers.length,
                    hasPassword: !!room.password,
                    useKnock: !!room.useKnock,
                });
            }
        });
        res.writeHead(200, {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*',
        });
        res.end(JSON.stringify(list));
        return;
    }

    if (url.pathname === '/' || url.pathname === '') {
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(roomDiscoveryPage());
        return;
    }

    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not found\n');
});

// ---------------------------------------------------------------------------
// WebSocket server
// ---------------------------------------------------------------------------

const wss = new WebSocket.Server({ server });

/**
 * rooms: Map<roomId, {
 *   broadcaster: WebSocket,
 *   viewers: WebSocket[],
 *   password: string|null,
 *   maxViewers: number,       // 0 = unlimited
 *   useKnock: boolean,
 *   hidden: boolean,          // excluded from /rooms discovery
 *   pendingViewers: Map<viewerId, WebSocket>,
 *   idleTimer: ReturnType<typeof setTimeout>|null,
 * }>
 */
const rooms = new Map();

wss.on('connection', (ws) => {
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });

    ws.on('message', (data) => {
        let msg;
        try {
            msg = JSON.parse(data.toString());
        } catch {
            ws.send(JSON.stringify({ type: 'error', message: 'Invalid JSON' }));
            return;
        }
        handleMessage(ws, msg);
    });

    ws.on('close', () => handleDisconnect(ws));
    ws.on('error', (err) => console.error('WS error:', err.message));
});

function handleMessage(ws, msg) {
    switch (msg.type) {

        // ------------------------------------------------------------------
        // Room management
        // ------------------------------------------------------------------

        case 'create': {
            const { roomId, password, maxViewers, useKnock, hidden } = msg;
            if (!roomId) { return sendError(ws, 'roomId required'); }
            if (rooms.has(roomId)) { return sendError(ws, 'Room already exists'); }
            const room = {
                broadcaster: ws,
                viewers: [],
                password: password || null,
                maxViewers: (Number.isInteger(maxViewers) && maxViewers > 0) ? maxViewers : 0,
                useKnock: !!useKnock,
                hidden: !!hidden,
                pendingViewers: new Map(),
                idleTimer: null,
            };
            rooms.set(roomId, room);
            ws.roomId = roomId;
            ws.role = 'broadcaster';
            ws.send(JSON.stringify({ type: 'created', roomId }));
            scheduleIdleExpiry(roomId);
            console.log(`Room created: ${roomId} (pw:${!!room.password} max:${room.maxViewers} knock:${room.useKnock})`);
            break;
        }

        case 'join': {
            const { roomId, password } = msg;
            const room = rooms.get(roomId);
            if (!room) { return sendError(ws, 'Room not found'); }

            // Password check
            if (room.password && room.password !== password) {
                return safeSend(ws, { type: 'rejected', reason: 'wrong_password' });
            }

            // Viewer limit check
            if (room.maxViewers > 0 && room.viewers.length >= room.maxViewers) {
                return safeSend(ws, { type: 'rejected', reason: 'room_full' });
            }

            if (room.useKnock) {
                // Place the viewer in the waiting room and notify broadcaster.
                const viewerId = randomUUID();
                room.pendingViewers.set(viewerId, ws);
                ws.roomId = roomId;
                ws.role = 'pending';
                ws.viewerId = viewerId;
                ws.send(JSON.stringify({ type: 'knock_sent' }));
                safeSend(room.broadcaster, {
                    type: 'viewer_knock',
                    viewerId,
                    displayName: msg.displayName || `Viewer ${room.viewers.length + room.pendingViewers.size}`,
                });
                console.log(`Viewer knocked on room: ${roomId} (${viewerId})`);
            } else {
                admitViewer(ws, room, roomId);
            }
            break;
        }

        // ------------------------------------------------------------------
        // Knock-to-enter flow
        // ------------------------------------------------------------------

        case 'accept': {
            const { viewerId } = msg;
            const room = rooms.get(ws.roomId);
            if (!room || ws.role !== 'broadcaster') { return; }
            const pendingWs = room.pendingViewers.get(viewerId);
            if (!pendingWs) { return; }
            room.pendingViewers.delete(viewerId);

            // Viewer limit re-check after the broadcaster approved.
            if (room.maxViewers > 0 && room.viewers.length >= room.maxViewers) {
                safeSend(pendingWs, { type: 'rejected', reason: 'room_full' });
                return;
            }

            safeSend(pendingWs, { type: 'knock_accepted' });
            admitViewer(pendingWs, room, ws.roomId);
            break;
        }

        case 'reject': {
            const { viewerId } = msg;
            const room = rooms.get(ws.roomId);
            if (!room || ws.role !== 'broadcaster') { return; }
            const pendingWs = room.pendingViewers.get(viewerId);
            if (!pendingWs) { return; }
            room.pendingViewers.delete(viewerId);
            safeSend(pendingWs, { type: 'knock_rejected' });
            console.log(`Viewer rejected from room: ${ws.roomId} (${viewerId})`);
            break;
        }

        // ------------------------------------------------------------------
        // WebRTC signaling
        // ------------------------------------------------------------------

        case 'offer': {
            const room = rooms.get(ws.roomId);
            if (!room || ws.role !== 'broadcaster') { return; }
            room.viewers.forEach(v => safeSend(v, { type: 'offer', sdp: msg.sdp }));
            break;
        }

        case 'answer': {
            const room = rooms.get(ws.roomId);
            if (!room || ws.role !== 'viewer') { return; }
            safeSend(room.broadcaster, { type: 'answer', sdp: msg.sdp });
            break;
        }

        case 'ice_candidate': {
            const room = rooms.get(ws.roomId);
            if (!room) { return; }
            if (ws.role === 'broadcaster') {
                room.viewers.forEach(v => safeSend(v, { type: 'ice_candidate', candidate: msg.candidate }));
            } else {
                safeSend(room.broadcaster, { type: 'ice_candidate', candidate: msg.candidate });
            }
            break;
        }

        default:
            sendError(ws, `Unknown message type: ${msg.type}`);
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Fully admit a viewer into the room and notify both sides. */
function admitViewer(ws, room, roomId) {
    cancelIdleExpiry(roomId);
    ws.roomId = roomId;
    ws.role = 'viewer';
    room.viewers.push(ws);
    ws.send(JSON.stringify({ type: 'joined', roomId }));
    safeSend(room.broadcaster, { type: 'viewer_joined' });
    broadcastViewerCount(room);
    console.log(`Viewer joined room: ${roomId} (total: ${room.viewers.length})`);
}

/** Send current viewer count to the broadcaster. */
function broadcastViewerCount(room) {
    safeSend(room.broadcaster, { type: 'viewer_count', count: room.viewers.length });
}

/** Start (or restart) a timer that deletes the room after ROOM_IDLE_MS with no viewers. */
function scheduleIdleExpiry(roomId) {
    const room = rooms.get(roomId);
    if (!room) { return; }
    cancelIdleExpiry(roomId);
    room.idleTimer = setTimeout(() => {
        const r = rooms.get(roomId);
        if (r && r.viewers.length === 0) {
            r.viewers.forEach(v => safeSend(v, { type: 'broadcast_ended' }));
            rooms.delete(roomId);
            console.log(`Room expired (idle): ${roomId}`);
        }
    }, ROOM_IDLE_MS);
}

function cancelIdleExpiry(roomId) {
    const room = rooms.get(roomId);
    if (room && room.idleTimer) {
        clearTimeout(room.idleTimer);
        room.idleTimer = null;
    }
}

function handleDisconnect(ws) {
    if (!ws.roomId) { return; }
    const room = rooms.get(ws.roomId);
    if (!room) { return; }

    if (ws.role === 'broadcaster') {
        room.viewers.forEach(v => safeSend(v, { type: 'broadcast_ended' }));
        // Also reject all pending viewers.
        room.pendingViewers.forEach(v => safeSend(v, { type: 'rejected', reason: 'broadcast_ended' }));
        cancelIdleExpiry(ws.roomId);
        rooms.delete(ws.roomId);
        console.log(`Room closed: ${ws.roomId}`);
    } else if (ws.role === 'pending') {
        room.pendingViewers.delete(ws.viewerId);
    } else {
        room.viewers = room.viewers.filter(v => v !== ws);
        broadcastViewerCount(room);
        console.log(`Viewer left room: ${ws.roomId} (remaining: ${room.viewers.length})`);
        if (room.viewers.length === 0) {
            scheduleIdleExpiry(ws.roomId);
        }
    }
}

function safeSend(ws, data) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(data));
    }
}

function sendError(ws, message) {
    safeSend(ws, { type: 'error', message });
}

// ---------------------------------------------------------------------------
// Room discovery HTML page
// ---------------------------------------------------------------------------

function roomDiscoveryPage() {
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>ScreenShare – Active Rooms</title>
<style>
  body{font-family:system-ui,sans-serif;max-width:640px;margin:40px auto;padding:0 16px;background:#111;color:#eee}
  h1{font-size:1.6rem;margin-bottom:4px}
  p.sub{color:#888;margin-top:0}
  table{width:100%;border-collapse:collapse;margin-top:24px}
  th,td{padding:10px 12px;text-align:left;border-bottom:1px solid #333}
  th{color:#aaa;font-size:.8rem;text-transform:uppercase;letter-spacing:.05em}
  .badge{display:inline-block;padding:2px 8px;border-radius:999px;font-size:.75rem}
  .badge-lock{background:#44334a;color:#d9a0ff}
  .badge-knock{background:#2a3d44;color:#7dd3fc}
  #refresh{background:#6200ee;color:#fff;border:none;padding:8px 18px;border-radius:6px;cursor:pointer;margin-top:20px}
  #refresh:hover{background:#3700b3}
</style>
</head>
<body>
<h1>📡 ScreenShare</h1>
<p class="sub">Active public rooms – refreshes every 10 s</p>
<table>
  <thead><tr><th>Room ID</th><th>Viewers</th><th>Options</th></tr></thead>
  <tbody id="tbody"><tr><td colspan="3" style="color:#666">Loading…</td></tr></tbody>
</table>
<button id="refresh" onclick="load()">Refresh now</button>
<script>
async function load(){
  const tbody=document.getElementById('tbody');
  try{
    const r=await fetch('/rooms');
    const list=await r.json();
    if(!list.length){tbody.innerHTML='<tr><td colspan="3" style="color:#666">No active rooms</td></tr>';return;}
    tbody.innerHTML=list.map(room=>{
      const badges=[
        room.hasPassword?'<span class="badge badge-lock">🔒 Password</span>':'',
        room.useKnock?'<span class="badge badge-knock">🚪 Knock</span>':'',
      ].filter(Boolean).join(' ');
      return '<tr><td><b>'+room.roomId+'</b></td><td>'+room.viewerCount+'</td><td>'+(badges||'—')+'</td></tr>';
    }).join('');
  }catch(e){tbody.innerHTML='<tr><td colspan="3" style="color:#f66">Failed to load</td></tr>';}
}
load();
setInterval(load,10000);
</script>
</body>
</html>`;
}

// ---------------------------------------------------------------------------
// Keep-alive ping every 30 s
// ---------------------------------------------------------------------------

const pingInterval = setInterval(() => {
    wss.clients.forEach((ws) => {
        if (!ws.isAlive) { ws.terminate(); return; }
        ws.isAlive = false;
        ws.ping();
    });
}, 30000);

wss.on('close', () => clearInterval(pingInterval));

server.listen(PORT, () => {
    console.log(`Signaling server listening on port ${PORT}`);
});
