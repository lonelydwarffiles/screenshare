/**
 * WebSocket signaling server for the ScreenShare Android app.
 *
 * Strict 1:1 model: one broadcaster, one viewer per session.
 * Messages are plain JSON objects with a "type" field.
 *
 * Client → server:
 *   create        { roomId, password? }   – broadcaster registers a session slug
 *   join          { roomId, password? }   – viewer connects to a session
 *   offer         { sdp }                 – broadcaster → viewer
 *   answer        { sdp }                 – viewer → broadcaster
 *   ice_candidate { candidate }           – relayed to the other peer
 *
 * Server → client:
 *   created        { roomId }             – session registered successfully
 *   joined         { roomId }             – viewer admitted
 *   viewer_joined  {}                     – broadcaster notified viewer arrived
 *   viewer_left    {}                     – broadcaster notified viewer disconnected
 *   broadcast_ended {}                    – viewer notified broadcaster left
 *   rejected       { reason }             – viewer turned away (not_found | wrong_password | session_full)
 *   error          { message }
 *
 * HTTP endpoints:
 *   GET /   – health check
 *
 * Session expiry: a session is deleted 5 min after the broadcaster disconnects.
 */

'use strict';

const http = require('http');
const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;
/** A session is deleted this many ms after the broadcaster disconnects. */
const SESSION_EXPIRE_MS = 5 * 60 * 1000; // 5 minutes

// ---------------------------------------------------------------------------
// HTTP server (health check only)
// ---------------------------------------------------------------------------

const server = http.createServer((req, res) => {
    if (req.method === 'GET' && (req.url === '/' || req.url === '')) {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('ScreenShare signaling server OK\n');
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
 * sessions: Map<slug, {
 *   broadcaster: WebSocket,
 *   viewer:      WebSocket | null,
 *   password:    string    | null,
 *   expireTimer: ReturnType<typeof setTimeout> | null,
 * }>
 */
const sessions = new Map();

wss.on('connection', (ws) => {
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });

    ws.on('message', (data) => {
        let msg;
        try {
            msg = JSON.parse(data.toString());
        } catch {
            safeSend(ws, { type: 'error', message: 'Invalid JSON' });
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
        // Session management
        // ------------------------------------------------------------------

        case 'create': {
            const { roomId, password } = msg;
            if (!roomId) { return safeSend(ws, { type: 'error', message: 'roomId required' }); }
            if (sessions.has(roomId)) { return safeSend(ws, { type: 'error', message: 'Session already exists' }); }
            sessions.set(roomId, {
                broadcaster: ws,
                viewer: null,
                password: password || null,
                expireTimer: null,
            });
            ws.sessionId = roomId;
            ws.role = 'broadcaster';
            safeSend(ws, { type: 'created', roomId });
            console.log(`Session created: ${roomId}`);
            break;
        }

        case 'join': {
            const { roomId, password } = msg;
            const session = sessions.get(roomId);
            if (!session) { return safeSend(ws, { type: 'rejected', reason: 'not_found' }); }
            if (session.password && session.password !== password) {
                return safeSend(ws, { type: 'rejected', reason: 'wrong_password' });
            }
            if (session.viewer !== null) {
                return safeSend(ws, { type: 'rejected', reason: 'session_full' });
            }
            cancelExpiry(roomId);
            session.viewer = ws;
            ws.sessionId = roomId;
            ws.role = 'viewer';
            safeSend(ws, { type: 'joined', roomId });
            safeSend(session.broadcaster, { type: 'viewer_joined' });
            console.log(`Viewer joined session: ${roomId}`);
            break;
        }

        // ------------------------------------------------------------------
        // WebRTC signaling – relayed directly to the other peer
        // ------------------------------------------------------------------

        case 'offer': {
            const session = sessions.get(ws.sessionId);
            if (!session || ws.role !== 'broadcaster' || !session.viewer) { return; }
            safeSend(session.viewer, { type: 'offer', sdp: msg.sdp });
            break;
        }

        case 'answer': {
            const session = sessions.get(ws.sessionId);
            if (!session || ws.role !== 'viewer') { return; }
            safeSend(session.broadcaster, { type: 'answer', sdp: msg.sdp });
            break;
        }

        case 'ice_candidate': {
            const session = sessions.get(ws.sessionId);
            if (!session) { return; }
            const peer = ws.role === 'broadcaster' ? session.viewer : session.broadcaster;
            if (peer) safeSend(peer, { type: 'ice_candidate', candidate: msg.candidate });
            break;
        }

        default:
            safeSend(ws, { type: 'error', message: `Unknown type: ${msg.type}` });
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function handleDisconnect(ws) {
    if (!ws.sessionId) { return; }
    const session = sessions.get(ws.sessionId);
    if (!session) { return; }

    if (ws.role === 'broadcaster') {
        if (session.viewer) {
            safeSend(session.viewer, { type: 'broadcast_ended' });
        }
        cancelExpiry(ws.sessionId);
        // Keep the session alive briefly so the broadcaster can reconnect.
        scheduleExpiry(ws.sessionId);
        console.log(`Broadcaster disconnected: ${ws.sessionId}`);
    } else if (ws.role === 'viewer') {
        session.viewer = null;
        safeSend(session.broadcaster, { type: 'viewer_left' });
        scheduleExpiry(ws.sessionId);
        console.log(`Viewer left session: ${ws.sessionId}`);
    }
}

function scheduleExpiry(sessionId) {
    const session = sessions.get(sessionId);
    if (!session) { return; }
    cancelExpiry(sessionId);
    session.expireTimer = setTimeout(() => {
        if (sessions.has(sessionId)) {
            sessions.delete(sessionId);
            console.log(`Session expired: ${sessionId}`);
        }
    }, SESSION_EXPIRE_MS);
}

function cancelExpiry(sessionId) {
    const session = sessions.get(sessionId);
    if (session && session.expireTimer) {
        clearTimeout(session.expireTimer);
        session.expireTimer = null;
    }
}

function safeSend(ws, data) {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(data));
    }
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
