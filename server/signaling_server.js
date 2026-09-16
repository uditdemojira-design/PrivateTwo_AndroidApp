/**
 * PrivateTwo Ephemeral Signaling Server
 *
 * STRICT PRIVACY GUARANTEES:
 * 1. ZERO PERSISTENT STORAGE: No databases, no disk writes, no message caching.
 * 2. EPHEMERAL RELAY ONLY: Sockets relay end-to-end encrypted payloads and WebRTC SDP/ICE.
 * 3. STRICT 2-DEVICE LIMIT: Rooms accept exactly two devices. Any 3rd device is instantly rejected.
 * 4. SHORT-LIVED PAIRING CODES: Pairing rendezvous codes expire in 180 seconds.
 */

const http = require('http');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = process.env.PORT || 8088;
const PAIRING_CODE_TTL_MS = 180 * 1000; // 3 minutes

const server = http.createServer((req, res) => {
    if (req.url === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ status: 'healthy', activeSessions: sessions.size }));
    } else {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('PrivateTwo Ephemeral Signaling Server. No content is stored.');
    }
});

const wss = new WebSocketServer({ server });

const pairingCodes = new Map();
const sessions = new Map();
const wsToSession = new Map();

wss.on('connection', (ws, req) => {
    ws.isAlive = true;
    const clientIp = req.socket.remoteAddress;
    console.log(`[Signaling] New WebSocket connection from ${clientIp}`);
    ws.on('pong', () => { ws.isAlive = true; });

    ws.on('message', (data) => {
        try {
            const message = JSON.parse(data.toString());
            console.log(`[Signaling] Received message: ${message.type}`);
            handleMessage(ws, message);
        } catch (err) {
            console.error(`[Signaling] Malformed JSON: ${err.message}`);
            sendError(ws, 'MALFORMED_JSON', 'Invalid JSON message payload');
        }
    });

    ws.on('close', () => {
        console.log(`[Signaling] Client disconnected`);
        handleDisconnect(ws);
    });

    ws.on('error', (err) => {
        console.error(`[Signaling] Socket error: ${err.message}`);
        handleDisconnect(ws);
    });
});

function handleMessage(ws, msg) {
    const type = msg.type;

    switch (type) {
        case 'REGISTER_PAIRING_CODE': {
            const code = msg.code;
            const deviceId = msg.deviceId;
            console.log(`[Signaling] Registering pairing code ${code} for device ${deviceId}`);
            if (!code || !deviceId) {
                return sendError(ws, 'INVALID_REQUEST', 'Missing code or deviceId');
            }

            cleanPairingCodeByWs(ws);

            const timeoutId = setTimeout(() => {
                pairingCodes.delete(code);
                if (ws.readyState === WebSocket.OPEN) {
                    ws.send(JSON.stringify({ type: 'PAIRING_CODE_EXPIRED', code }));
                }
            }, PAIRING_CODE_TTL_MS);

            pairingCodes.set(code, {
                initiatorWs: ws,
                initiatorDeviceId: deviceId,
                createdAt: Date.now(),
                timeoutId
            });

            ws.send(JSON.stringify({ type: 'PAIRING_CODE_REGISTERED', code, expiresInSeconds: 180 }));
            break;
        }

        case 'JOIN_PAIRING_CODE': {
            const code = msg.code;
            const joinerDeviceId = msg.deviceId;
            const entry = pairingCodes.get(code);

            if (!entry) {
                return sendError(ws, 'PAIRING_CODE_NOT_FOUND', 'Pairing code does not exist or has expired');
            }

            if (entry.initiatorDeviceId === joinerDeviceId) {
                return sendError(ws, 'CANNOT_PAIR_SELF', 'Cannot pair a device with itself');
            }

            clearTimeout(entry.timeoutId);
            pairingCodes.delete(code);

            const sessionId = `session_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`;
            const session = {
                id: sessionId,
                deviceA: entry.initiatorWs,
                deviceAId: entry.initiatorDeviceId,
                deviceB: ws,
                deviceBId: joinerDeviceId
            };

            sessions.set(sessionId, session);
            wsToSession.set(entry.initiatorWs, sessionId);
            wsToSession.set(ws, sessionId);

            entry.initiatorWs.send(JSON.stringify({
                type: 'PEER_JOINED_PAIRING',
                sessionId,
                peerDeviceId: joinerDeviceId
            }));

            ws.send(JSON.stringify({
                type: 'PAIRING_ACCEPTED',
                sessionId,
                peerDeviceId: entry.initiatorDeviceId
            }));
            break;
        }

        case 'JOIN_SESSION': {
            const sessionId = msg.sessionId;
            const deviceId = msg.deviceId;
            const expectedPeerId = msg.expectedPeerId;

            if (!sessionId || !deviceId || !expectedPeerId) {
                return sendError(ws, 'INVALID_REQUEST', 'Missing sessionId, deviceId, or expectedPeerId');
            }

            let session = sessions.get(sessionId);
            if (!session) {
                session = {
                    id: sessionId,
                    deviceA: ws,
                    deviceAId: deviceId,
                    deviceB: null,
                    deviceBId: expectedPeerId
                };
                sessions.set(sessionId, session);
                wsToSession.set(ws, sessionId);
                ws.send(JSON.stringify({ type: 'SESSION_WAITING_FOR_PEER', sessionId }));
            } else {
                if (session.deviceB !== null && session.deviceA !== null) {
                    return sendError(ws, 'SESSION_FULL', 'Max 2 devices allowed. Third device rejected.');
                }

                const isExpected = (deviceId === session.deviceBId && expectedPeerId === session.deviceAId) ||
                                  (deviceId === session.deviceAId && expectedPeerId === session.deviceBId);

                if (!isExpected) {
                    return sendError(ws, 'UNAUTHORIZED_PEER', 'Identity does not match paired credentials');
                }

                if (!session.deviceA) {
                    session.deviceA = ws;
                    session.deviceAId = deviceId;
                } else {
                    session.deviceB = ws;
                    session.deviceBId = deviceId;
                }

                wsToSession.set(ws, sessionId);

                const peerWs = (session.deviceA === ws) ? session.deviceB : session.deviceA;
                ws.send(JSON.stringify({ type: 'PEER_CONNECTED', peerDeviceId: expectedPeerId }));
                if (peerWs && peerWs.readyState === WebSocket.OPEN) {
                    peerWs.send(JSON.stringify({ type: 'PEER_CONNECTED', peerDeviceId: deviceId }));
                }
            }
            break;
        }

        case 'PAIR_HANDSHAKE':
        case 'SDP_OFFER':
        case 'SDP_ANSWER':
        case 'ICE_CANDIDATE':
        case 'CALL_OFFER':
        case 'CALL_ANSWER':
        case 'CALL_END':
        case 'E2EE_ENVELOPE': {
            const sessionId = wsToSession.get(ws);
            if (!sessionId) {
                return sendError(ws, 'NOT_IN_SESSION', 'Device is not part of an active session');
            }

            const session = sessions.get(sessionId);
            if (!session) {
                return sendError(ws, 'SESSION_EXPIRED', 'Session does not exist');
            }

            const partnerWs = (session.deviceA === ws) ? session.deviceB : session.deviceA;
            if (!partnerWs || partnerWs.readyState !== WebSocket.OPEN) {
                return sendError(ws, 'PEER_OFFLINE', 'Partner device is currently offline');
            }

            partnerWs.send(JSON.stringify(msg));
            break;
        }

        default:
            sendError(ws, 'UNKNOWN_MESSAGE_TYPE', `Unknown type: ${type}`);
    }
}

function handleDisconnect(ws) {
    cleanPairingCodeByWs(ws);

    const sessionId = wsToSession.get(ws);
    if (sessionId) {
        wsToSession.delete(ws);
        const session = sessions.get(sessionId);
        if (session) {
            const partnerWs = (session.deviceA === ws) ? session.deviceB : session.deviceA;
            if (partnerWs && partnerWs.readyState === WebSocket.OPEN) {
                partnerWs.send(JSON.stringify({ type: 'PEER_DISCONNECTED' }));
            }
            if (session.deviceA === ws) session.deviceA = null;
            if (session.deviceB === ws) session.deviceB = null;

            if (!session.deviceA && !session.deviceB) {
                sessions.delete(sessionId);
            }
        }
    }
}

function cleanPairingCodeByWs(ws) {
    for (const [code, entry] of pairingCodes.entries()) {
        if (entry.initiatorWs === ws) {
            clearTimeout(entry.timeoutId);
            pairingCodes.delete(code);
        }
    }
}

function sendError(ws, code, message) {
    if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'ERROR', code, message }));
    }
}

setInterval(() => {
    wss.clients.forEach((ws) => {
        if (!ws.isAlive) {
            handleDisconnect(ws);
            return ws.terminate();
        }
        ws.isAlive = false;
        ws.ping();
    });
}, 30000);

server.listen(PORT, () => {
    console.log(`PrivateTwo Signaling Server listening on port ${PORT}`);
    console.log(`Ephemeral mode active: ZERO data stored.`);
});
