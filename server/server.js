/**
 * Simple WebSocket signaling server for the ScreenShare Android app.
 *
 * Rooms: one broadcaster streams to one or more viewers.
 * Messages are plain JSON objects with a "type" field.
 */

'use strict';

const http = require('http');
const WebSocket = require('ws');

const PORT = process.env.PORT || 8080;

const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ScreenShare Signaling Server\n');
});

const wss = new WebSocket.Server({ server });

/**
 * rooms: Map<roomId, { broadcaster: WebSocket, viewers: WebSocket[] }>
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
        case 'create': {
            const { roomId } = msg;
            if (!roomId) { return sendError(ws, 'roomId required'); }
            if (rooms.has(roomId)) { return sendError(ws, 'Room already exists'); }
            rooms.set(roomId, { broadcaster: ws, viewers: [] });
            ws.roomId = roomId;
            ws.role = 'broadcaster';
            ws.send(JSON.stringify({ type: 'created', roomId }));
            console.log(`Room created: ${roomId}`);
            break;
        }

        case 'join': {
            const { roomId } = msg;
            const room = rooms.get(roomId);
            if (!room) { return sendError(ws, 'Room not found'); }
            ws.roomId = roomId;
            ws.role = 'viewer';
            room.viewers.push(ws);
            ws.send(JSON.stringify({ type: 'joined', roomId }));
            safeSend(room.broadcaster, { type: 'viewer_joined' });
            console.log(`Viewer joined room: ${roomId}`);
            break;
        }

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

function handleDisconnect(ws) {
    if (!ws.roomId) { return; }
    const room = rooms.get(ws.roomId);
    if (!room) { return; }

    if (ws.role === 'broadcaster') {
        room.viewers.forEach(v => safeSend(v, { type: 'broadcast_ended' }));
        rooms.delete(ws.roomId);
        console.log(`Room closed: ${ws.roomId}`);
    } else {
        room.viewers = room.viewers.filter(v => v !== ws);
        console.log(`Viewer left room: ${ws.roomId}`);
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

// Keep-alive ping every 30 s
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
