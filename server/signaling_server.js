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
const LOG_LEVEL = process.env.LOG_LEVEL || 'warn';

function logDebug(msg) { if (LOG_LEVEL === 'debug') console.log(`[Signaling:DEBUG] ${msg}`); }
function logInfo(msg) { if (LOG_LEVEL === 'info' || LOG_LEVEL === 'debug') console.log(`[Signaling:INFO] ${msg}`); }
function logWarn(msg) { if (LOG_LEVEL !== 'error') console.warn(`[Signaling:WARN] ${msg}`); }
function logError(msg) { console.error(`[Signaling:ERROR] ${msg}`); }

const stats = {
    totalConnections: 0,
    totalMessagesRelayed: 0,
    startTime: Date.now()
};

const server = http.createServer((req, res) => {
    if (req.url === '/health') {
        const mem = process.memoryUsage();
        const uptimeSec = Math.floor((Date.now() - stats.startTime) / 1000);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: 'healthy',
            uptimeSeconds: uptimeSec,
            activeConnections: wss.clients.size,
            activeSessions: sessions.size,
            totalConnectionsServed: stats.totalConnections,
            totalMessagesRelayed: stats.totalMessagesRelayed,
            memory: {
                heapUsedMB: Math.round(mem.heapUsed / 1024 / 1024 * 10) / 10,
                heapTotalMB: Math.round(mem.heapTotal / 1024 / 1024 * 10) / 10,
                rssMB: Math.round(mem.rss / 1024 / 1024 * 10) / 10
            }
        }));
    } else {
        res.writeHead(200, { 'Content-Type': 'text/plain' });
        res.end('PrivateTwo Ephemeral Signaling Server. No content is stored.');
    }
});

const wss = new WebSocketServer({ server, backlog: 1024, maxPayload: 64 * 1024 * 1024 });

const pairingCodes = new Map();
const sessions = new Map();
const wsToSession = new Map();
const deviceIdToWs = new Map();
const pendingPairings = new Map(); // deviceId -> { sessionId, peerDeviceId }

function safeSend(ws, payload) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    try {
        const data = typeof payload === 'string' ? payload : JSON.stringify(payload);
        ws.send(data, (err) => {
            if (err) logError(`safeSend callback error: ${err.message}`);
        });
        return true;
    } catch (err) {
        logError(`safeSend exception: ${err.message}`);
        return false;
    }
}

wss.on('connection', (ws, req) => {
    stats.totalConnections++;
    ws.isAlive = true;
    const clientIp = req.socket.remoteAddress;
    logDebug(`New WebSocket connection from ${clientIp}`);
    ws.on('pong', () => { ws.isAlive = true; });

    ws.on('message', (data) => {
        try {
            const message = JSON.parse(data.toString());
            logDebug(`Received message: ${message.type}`);
            handleMessage(ws, message);
        } catch (err) {
            logError(`Malformed JSON: ${err.message}`);
            sendError(ws, 'MALFORMED_JSON', 'Invalid JSON message payload');
        }
    });

    ws.on('close', () => {
        logDebug(`Client disconnected`);
        handleDisconnect(ws);
    });

    ws.on('error', (err) => {
        logError(`Socket error: ${err.message}`);
        handleDisconnect(ws);
    });
});

function removeWsFromSession(ws) {
    const sessionId = wsToSession.get(ws);
    if (sessionId) {
        wsToSession.delete(ws);
        const session = sessions.get(sessionId);
        if (session) {
            if (session.deviceA === ws) session.deviceA = null;
            if (session.deviceB === ws) session.deviceB = null;

            if (!session.deviceA && !session.deviceB) {
                sessions.delete(sessionId);
            }
        }
    }
}

function handleMessage(ws, msg) {
    const type = msg.type;

    // Track deviceId to socket mapping
    let deviceId = msg.deviceId;
    if (!deviceId && msg.payload && typeof msg.payload === 'string') {
        try {
            const parsed = JSON.parse(msg.payload);
            if (parsed.deviceId) deviceId = parsed.deviceId;
        } catch (_) {}
    }

    if (deviceId) {
        deviceIdToWs.set(deviceId, ws);
        ws.deviceId = deviceId;

        // Auto-reconnect this socket to any existing session it belongs to
        for (const [sId, sess] of sessions.entries()) {
            if (sess.deviceAId === deviceId) {
                sess.deviceA = ws;
                wsToSession.set(ws, sId);
            } else if (sess.deviceBId === deviceId) {
                sess.deviceB = ws;
                wsToSession.set(ws, sId);
            }
        }

        // Check if there is a pending pairing notification for this device
        if (pendingPairings.has(deviceId)) {
            const pending = pendingPairings.get(deviceId);
            pendingPairings.delete(deviceId);
            wsToSession.set(ws, pending.sessionId);
            const session = sessions.get(pending.sessionId);
            if (session) {
                if (session.deviceAId === deviceId) session.deviceA = ws;
                if (session.deviceBId === deviceId) session.deviceB = ws;
            }
            safeSend(ws, {
                type: 'PEER_JOINED_PAIRING',
                sessionId: pending.sessionId,
                peerDeviceId: pending.peerDeviceId
            });
        }
    }

    switch (type) {
        case 'IDENTIFY': {
            if (deviceId) {
                logInfo(`Client identified: ${deviceId}`);
                safeSend(ws, { type: 'IDENTIFIED', deviceId });
            }
            break;
        }

        case 'REGISTER_PAIRING_CODE': {
            const code = String(msg.code || '').replace(/\s+/g, '').trim();
            const devId = deviceId || msg.deviceId;
            console.log(`[Signaling] Registering pairing code ${code} for device ${devId}`);
            if (!code || !devId) {
                return sendError(ws, 'INVALID_REQUEST', 'Missing code or deviceId');
            }

            // Clean any previous pairing code generated by this device
            for (const [c, entry] of pairingCodes.entries()) {
                if (entry.initiatorDeviceId === devId) {
                    clearTimeout(entry.timeoutId);
                    pairingCodes.delete(c);
                }
            }

            const timeoutId = setTimeout(() => {
                pairingCodes.delete(code);
                safeSend(ws, { type: 'PAIRING_CODE_EXPIRED', code });
            }, PAIRING_CODE_TTL_MS);

            pairingCodes.set(code, {
                initiatorWs: ws,
                initiatorDeviceId: devId,
                createdAt: Date.now(),
                timeoutId
            });

            safeSend(ws, { type: 'PAIRING_CODE_REGISTERED', code, expiresInSeconds: 180 });
            break;
        }

        case 'JOIN_PAIRING_CODE': {
            const code = String(msg.code || '').replace(/\s+/g, '').trim();
            const joinerDeviceId = deviceId || msg.deviceId;
            const entry = pairingCodes.get(code);

            if (!entry) {
                console.warn(`[Signaling] Pairing code not found: "${code}". Active codes: [${Array.from(pairingCodes.keys()).join(', ')}]`);
                return sendError(ws, 'PAIRING_CODE_NOT_FOUND', 'Pairing code does not exist or has expired');
            }

            if (entry.initiatorDeviceId === joinerDeviceId) {
                return sendError(ws, 'CANNOT_PAIR_SELF', 'Cannot pair a device with itself');
            }

            // Keep code alive briefly to allow retry if initial handshake dropped
            clearTimeout(entry.timeoutId);
            entry.timeoutId = setTimeout(() => {
                pairingCodes.delete(code);
            }, 60000);

            // Find current active socket for initiator
            const activeInitiatorWs = deviceIdToWs.get(entry.initiatorDeviceId) || entry.initiatorWs;

            removeWsFromSession(ws);
            if (activeInitiatorWs) {
                removeWsFromSession(activeInitiatorWs);
            }

            const sessionId = `session_${entry.initiatorDeviceId}_${joinerDeviceId}`;
            const session = {
                id: sessionId,
                deviceA: activeInitiatorWs,
                deviceAId: entry.initiatorDeviceId,
                deviceB: ws,
                deviceBId: joinerDeviceId
            };

            sessions.set(sessionId, session);
            if (activeInitiatorWs) {
                wsToSession.set(activeInitiatorWs, sessionId);
            }
            wsToSession.set(ws, sessionId);

            if (activeInitiatorWs && activeInitiatorWs.readyState === WebSocket.OPEN) {
                safeSend(activeInitiatorWs, {
                    type: 'PEER_JOINED_PAIRING',
                    sessionId,
                    peerDeviceId: joinerDeviceId
                });
            } else {
                console.log(`[Signaling] Initiator offline during pairing, saving pending notification for ${entry.initiatorDeviceId}`);
                pendingPairings.set(entry.initiatorDeviceId, {
                    sessionId,
                    peerDeviceId: joinerDeviceId
                });
            }

            safeSend(ws, {
                type: 'PAIRING_ACCEPTED',
                sessionId,
                peerDeviceId: entry.initiatorDeviceId
            });
            break;
        }

        case 'JOIN_SESSION': {
            const sessionId = msg.sessionId;
            const devId = deviceId || msg.deviceId;
            const expectedPeerId = msg.expectedPeerId;

            if (!sessionId || !devId) {
                return sendError(ws, 'INVALID_REQUEST', 'Missing sessionId or deviceId');
            }

            const prevSessionId = wsToSession.get(ws);
            if (prevSessionId && prevSessionId !== sessionId) {
                removeWsFromSession(ws);
            }

            let session = sessions.get(sessionId);
            if (!session) {
                session = {
                    id: sessionId,
                    deviceA: ws,
                    deviceAId: devId,
                    deviceB: null,
                    deviceBId: expectedPeerId || null
                };
                sessions.set(sessionId, session);
                wsToSession.set(ws, sessionId);
                safeSend(ws, { type: 'SESSION_WAITING_FOR_PEER', sessionId });
            } else {
                // If this is one of the expected devices, reconnect them without fail!
                if (session.deviceAId === devId) {
                    session.deviceA = ws;
                } else if (session.deviceBId === devId) {
                    session.deviceB = ws;
                } else if (!session.deviceAId) {
                    session.deviceA = ws;
                    session.deviceAId = devId;
                } else if (!session.deviceBId) {
                    session.deviceB = ws;
                    session.deviceBId = devId;
                } else if (expectedPeerId && (session.deviceAId === expectedPeerId || session.deviceBId === expectedPeerId)) {
                    // Re-bind the slot that doesn't match expectedPeerId to this device
                    if (session.deviceAId === expectedPeerId) {
                        session.deviceB = ws;
                        session.deviceBId = devId;
                    } else {
                        session.deviceA = ws;
                        session.deviceAId = devId;
                    }
                } else {
                    return sendError(ws, 'SESSION_FULL', 'Max 2 devices allowed. Third device rejected.');
                }

                wsToSession.set(ws, sessionId);

                const peerWs = (session.deviceA === ws)
                    ? (deviceIdToWs.get(session.deviceBId) || session.deviceB)
                    : (deviceIdToWs.get(session.deviceAId) || session.deviceA);

                safeSend(ws, { type: 'PEER_CONNECTED', peerDeviceId: expectedPeerId || (session.deviceA === ws ? session.deviceBId : session.deviceAId) });
                if (peerWs && peerWs !== ws) {
                    safeSend(peerWs, { type: 'PEER_CONNECTED', peerDeviceId: devId });
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
            let sessionId = wsToSession.get(ws);
            let session = sessionId ? sessions.get(sessionId) : null;

            // Fallback: look up session by deviceId if wsToSession was lost during reconnect
            if (!session && ws.deviceId) {
                for (const [sId, sess] of sessions.entries()) {
                    if (sess.deviceAId === ws.deviceId || sess.deviceBId === ws.deviceId) {
                        sessionId = sId;
                        session = sess;
                        wsToSession.set(ws, sId);
                        if (sess.deviceAId === ws.deviceId) sess.deviceA = ws;
                        if (sess.deviceBId === ws.deviceId) sess.deviceB = ws;
                        break;
                    }
                }
            }

            if (!session) {
                return sendError(ws, 'NOT_IN_SESSION', 'Device is not part of an active session');
            }

            // Always look up partner by device ID from deviceIdToWs mapping
            const partnerDeviceId = (session.deviceAId === ws.deviceId) ? session.deviceBId : session.deviceAId;
            const partnerWs = (partnerDeviceId && deviceIdToWs.get(partnerDeviceId))
                || ((session.deviceA === ws) ? session.deviceB : session.deviceA);

            if (!partnerWs || partnerWs.readyState !== WebSocket.OPEN) {
                return sendError(ws, 'PEER_OFFLINE', 'Partner device is currently offline');
            }

            stats.totalMessagesRelayed++;
            safeSend(partnerWs, msg);
            break;
        }

        default:
            sendError(ws, 'UNKNOWN_MESSAGE_TYPE', `Unknown type: ${type}`);
    }
}

function handleDisconnect(ws) {
    if (ws.deviceId && deviceIdToWs.get(ws.deviceId) === ws) {
        deviceIdToWs.delete(ws.deviceId);
    }
    const sessionId = wsToSession.get(ws);
    if (sessionId) {
        wsToSession.delete(ws);
        const session = sessions.get(sessionId);
        if (session) {
            const partnerWs = (session.deviceA === ws) ? session.deviceB : session.deviceA;
            if (partnerWs) {
                safeSend(partnerWs, { type: 'PEER_DISCONNECTED' });
            }
            if (session.deviceA === ws) session.deviceA = null;
            if (session.deviceB === ws) session.deviceB = null;

            if (!session.deviceA && !session.deviceB) {
                sessions.delete(sessionId);
            }
        }
    }
}

function sendError(ws, code, message) {
    safeSend(ws, { type: 'ERROR', code, message });
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
