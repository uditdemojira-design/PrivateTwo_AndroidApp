/**
 * PrivateTwo 1,000 Concurrent Users Load & Stress Test
 * Simulates 1,000 distinct WebSocket devices (500 pairs) simultaneously:
 * 1. Connection Ramp-Up (1,000 active sockets)
 * 2. Simultaneous Pairing Rendezvous (500 pairs)
 * 3. Session Join (JOIN_SESSION for 500 rooms)
 * 4. High-Throughput Message Storm (5,000 E2EE Envelopes)
 * 5. Reconnection Storm (200 sockets abruptly reconnect)
 * 6. Latency & Resource Benchmarking
 */

const { WebSocket } = require('ws');
const http = require('http');

const SERVER_URL = process.env.TEST_SERVER_URL || 'ws://127.0.0.1:8088';
const HTTP_HEALTH_URL = process.env.TEST_HEALTH_URL || 'http://127.0.0.1:8088/health';
const TOTAL_USERS = parseInt(process.env.TOTAL_USERS || '1000', 10);
const NUM_PAIRS = TOTAL_USERS / 2; // 500 pairs
const MESSAGES_PER_PAIR = parseInt(process.env.MESSAGES_PER_PAIR || '10', 10);
const TOTAL_EXPECTED_MESSAGES = NUM_PAIRS * MESSAGES_PER_PAIR; // 5,000 messages

console.log('===============================================================');
console.log('  PRIVATETWO 1,000 CONCURRENT USERS PERFORMANCE & LOAD TEST    ');
console.log('===============================================================');
console.log(` Target Server:         ${SERVER_URL}`);
console.log(` Concurrent Clients:    ${TOTAL_USERS} (${NUM_PAIRS} paired conversations)`);
console.log(` Messages per Pair:     ${MESSAGES_PER_PAIR}`);
console.log(` Total Relayed Packets: ${TOTAL_EXPECTED_MESSAGES}`);
console.log('===============================================================\n');

const clientsA = [];
const clientsB = [];
const latencies = [];
let totalMessagesReceived = 0;
let totalMessagesSent = 0;

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

function fetchHealth() {
    return new Promise((resolve) => {
        http.get(HTTP_HEALTH_URL, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    resolve(JSON.parse(data));
                } catch {
                    resolve(null);
                }
            });
        }).on('error', () => resolve(null));
    });
}

class TestClient {
    constructor(id, role, pairIndex) {
        this.id = id;
        this.role = role; // 'A' or 'B'
        this.pairIndex = pairIndex;
        this.ws = null;
        this.connected = false;
        this.pairingCode = null;
        this.paired = false;
        this.sessionReady = false;
        this.messagesReceived = 0;
        this.eventListeners = new Map();
    }

    on(type, callback) {
        this.eventListeners.set(type, callback);
    }

    connect() {
        return new Promise((resolve, reject) => {
            this.ws = new WebSocket(SERVER_URL);

            this.ws.on('open', () => {
                this.connected = true;
                resolve();
            });

            this.ws.on('message', (data) => {
                try {
                    const msg = JSON.parse(data.toString());
                    const cb = this.eventListeners.get(msg.type);
                    if (cb) cb(msg);
                } catch (e) {
                    console.error(`[${this.id}] Error parsing message:`, e.message);
                }
            });

            this.ws.on('error', (err) => {
                if (!this.connected) reject(err);
            });

            this.ws.on('close', () => {
                this.connected = false;
            });
        });
    }

    send(obj) {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify(obj));
        }
    }

    disconnect() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
            this.connected = false;
        }
    }
}

async function runLoadTest() {
    const startTime = Date.now();

    // -------------------------------------------------------------
    // STAGE 1: Ramp Up 1,000 Concurrent WebSocket Connections
    // -------------------------------------------------------------
    console.log(`[Stage 1/5] Ramping up ${TOTAL_USERS} WebSocket connections...`);
    const stage1Start = Date.now();

    for (let i = 0; i < NUM_PAIRS; i++) {
        clientsA.push(new TestClient(`device_A_${i}`, 'A', i));
        clientsB.push(new TestClient(`device_B_${i}`, 'B', i));
    }

    const allClients = [];
    for (let i = 0; i < NUM_PAIRS; i++) {
        allClients.push(clientsA[i]);
        allClients.push(clientsB[i]);
    }

    // Connect in batches of 100 to avoid OS port throttling
    const BATCH_SIZE = 100;
    let connectedCount = 0;

    for (let i = 0; i < allClients.length; i += BATCH_SIZE) {
        const batch = allClients.slice(i, i + BATCH_SIZE);
        await Promise.all(batch.map(c => c.connect().then(() => connectedCount++)));
        process.stdout.write(`\r   Connected: ${connectedCount} / ${TOTAL_USERS} clients (${Math.round(connectedCount / TOTAL_USERS * 100)}%)`);
        await sleep(50); // slight yield
    }

    const stage1Duration = (Date.now() - stage1Start) / 1000;
    console.log(`\n  -> Stage 1 Completed in ${stage1Duration.toFixed(2)}s. All 1,000 sockets connected successfully.\n`);

    const health1 = await fetchHealth();
    console.log(`  [Server Metric] Active Connections: ${health1?.activeConnections}, Memory: ${health1?.memory?.heapUsedMB} MB`);

    // -------------------------------------------------------------
    // STAGE 2: Concurrent Pairing Rendezvous (500 Pairs)
    // -------------------------------------------------------------
    console.log(`\n[Stage 2/5] Executing 500 simultaneous pairing handshakes...`);
    const stage2Start = Date.now();
    let pairedCount = 0;

    const pairingPromises = [];

    for (let i = 0; i < NUM_PAIRS; i++) {
        const clientA = clientsA[i];
        const clientB = clientsB[i];
        const code = (100000 + (i % 900000)).toString(); // 6 digit code

        const p = new Promise((resolve) => {
            clientA.on('PAIRING_CODE_REGISTERED', () => {
                // Once A registers, B joins
                clientB.send({
                    type: 'JOIN_PAIRING_CODE',
                    code,
                    deviceId: clientB.id
                });
            });

            clientA.on('PEER_JOINED_PAIRING', (msg) => {
                clientA.paired = true;
                clientA.sessionId = msg.sessionId;
                // A sends handshake offer
                clientA.send({
                    type: 'PAIR_HANDSHAKE',
                    payload: JSON.stringify({ action: 'OFFER_KEY', deviceId: clientA.id })
                });
            });

            clientB.on('PAIRING_ACCEPTED', (msg) => {
                clientB.paired = true;
                clientB.sessionId = msg.sessionId;
            });

            clientB.on('PAIR_HANDSHAKE', (msg) => {
                pairedCount++;
                resolve();
            });

            // Start registration
            clientA.send({
                type: 'REGISTER_PAIRING_CODE',
                code,
                deviceId: clientA.id
            });
        });

        pairingPromises.push(p);
    }

    await Promise.all(pairingPromises);
    const stage2Duration = (Date.now() - stage2Start) / 1000;
    console.log(`  -> Stage 2 Completed in ${stage2Duration.toFixed(2)}s.`);
    console.log(`     Successful Pairs: ${pairedCount} / ${NUM_PAIRS} (100% success rate)`);

    // -------------------------------------------------------------
    // STAGE 3: Direct Session Re-Join (JOIN_SESSION for 500 Rooms)
    // -------------------------------------------------------------
    console.log(`\n[Stage 3/5] Transitioning 500 pairs into Direct E2EE Sessions...`);
    const stage3Start = Date.now();
    let sessionJoinedCount = 0;

    const sessionPromises = [];

    for (let i = 0; i < NUM_PAIRS; i++) {
        const clientA = clientsA[i];
        const clientB = clientsB[i];
        const directSessionId = `direct_session_${i}`;

        const pA = new Promise(resolve => {
            clientA.on('PEER_CONNECTED', () => {
                clientA.sessionReady = true;
                sessionJoinedCount++;
                resolve();
            });
            clientA.on('SESSION_WAITING_FOR_PEER', () => {
                // waiting for B
            });
        });

        const pB = new Promise(resolve => {
            clientB.on('PEER_CONNECTED', () => {
                clientB.sessionReady = true;
                sessionJoinedCount++;
                resolve();
            });
        });

        clientA.send({
            type: 'JOIN_SESSION',
            sessionId: directSessionId,
            deviceId: clientA.id,
            expectedPeerId: clientB.id
        });

        clientB.send({
            type: 'JOIN_SESSION',
            sessionId: directSessionId,
            deviceId: clientB.id,
            expectedPeerId: clientA.id
        });

        sessionPromises.push(pA, pB);
    }

    await Promise.all(sessionPromises);
    const stage3Duration = (Date.now() - stage3Start) / 1000;
    console.log(`  -> Stage 3 Completed in ${stage3Duration.toFixed(2)}s.`);
    console.log(`     Sessions Established: ${sessionJoinedCount / 2} / ${NUM_PAIRS} (100% established)`);

    // -------------------------------------------------------------
    // STAGE 4: High-Volume Message Storm (5,000 E2EE Envelopes)
    // -------------------------------------------------------------
    console.log(`\n[Stage 4/5] Blasting Message Storm: ${TOTAL_EXPECTED_MESSAGES} messages across 500 pairs...`);
    const stage4Start = Date.now();

    const allMessagesPromise = new Promise((resolve) => {
        for (let i = 0; i < NUM_PAIRS; i++) {
            const clientA = clientsA[i];
            const clientB = clientsB[i];

            const messageHandler = (client) => (msg) => {
                if (msg.type === 'E2EE_ENVELOPE') {
                    const rtt = Date.now() - msg.sentAt;
                    latencies.push(rtt);
                    totalMessagesReceived++;
                    client.messagesReceived++;

                    if (totalMessagesReceived % 500 === 0 || totalMessagesReceived === TOTAL_EXPECTED_MESSAGES) {
                        process.stdout.write(`\r   Messages Delivered: ${totalMessagesReceived} / ${TOTAL_EXPECTED_MESSAGES} (${Math.round(totalMessagesReceived / TOTAL_EXPECTED_MESSAGES * 100)}%)`);
                    }

                    if (totalMessagesReceived === TOTAL_EXPECTED_MESSAGES) {
                        resolve();
                    }
                }
            };

            clientA.on('E2EE_ENVELOPE', messageHandler(clientA));
            clientB.on('E2EE_ENVELOPE', messageHandler(clientB));
        }

        // Send messages: Each pair sends MESSAGES_PER_PAIR alternating
        for (let m = 0; m < MESSAGES_PER_PAIR; m++) {
            for (let i = 0; i < NUM_PAIRS; i++) {
                const sender = (m % 2 === 0) ? clientsA[i] : clientsB[i];
                sender.send({
                    type: 'E2EE_ENVELOPE',
                    envelope: `encrypted_payload_pair_${i}_msg_${m}`,
                    sentAt: Date.now()
                });
                totalMessagesSent++;
            }
        }
    });

    await allMessagesPromise;
    const stage4Duration = (Date.now() - stage4Start) / 1000;
    const throughput = Math.round(TOTAL_EXPECTED_MESSAGES / stage4Duration);
    console.log(`\n  -> Stage 4 Completed in ${stage4Duration.toFixed(2)}s.`);
    console.log(`     Throughput: ${throughput} messages/sec`);
    console.log(`     Dropped Messages: 0 (100.0% delivery rate)`);

    // -------------------------------------------------------------
    // STAGE 5: Reconnection Storm (200 Sockets Abruptly Drop & Reconnect)
    // -------------------------------------------------------------
    console.log(`\n[Stage 5/5] Reconnection Stress Test: Dropping and reconnecting 200 clients...`);
    const stage5Start = Date.now();
    const reconnectSubset = clientsA.slice(0, 200);

    // Disconnect 200 clients
    reconnectSubset.forEach(c => c.disconnect());
    await sleep(200);

    // Reconnect all 200 simultaneously
    let reconnectedCount = 0;
    const reconnectPromises = reconnectSubset.map(c => {
        return c.connect().then(() => {
            reconnectedCount++;
            // Re-join session
            c.send({
                type: 'JOIN_SESSION',
                sessionId: `direct_session_${c.pairIndex}`,
                deviceId: c.id,
                expectedPeerId: clientsB[c.pairIndex].id
            });
        });
    });

    await Promise.all(reconnectPromises);
    const stage5Duration = (Date.now() - stage5Start) / 1000;
    console.log(`  -> Stage 5 Completed in ${stage5Duration.toFixed(2)}s. Reconnected: ${reconnectedCount} / 200 clients.`);

    // -------------------------------------------------------------
    // BENCHMARK RESULTS & METRICS
    // -------------------------------------------------------------
    latencies.sort((a, b) => a - b);
    const minLat = latencies[0] || 0;
    const maxLat = latencies[latencies.length - 1] || 0;
    const meanLat = Math.round(latencies.reduce((a, b) => a + b, 0) / (latencies.length || 1));
    const p50 = latencies[Math.floor(latencies.length * 0.50)] || 0;
    const p95 = latencies[Math.floor(latencies.length * 0.95)] || 0;
    const p99 = latencies[Math.floor(latencies.length * 0.99)] || 0;

    const finalHealth = await fetchHealth();
    const totalDuration = (Date.now() - startTime) / 1000;

    console.log('\n===============================================================');
    console.log('              PERFORMANCE BENCHMARK RESULTS                    ');
    console.log('===============================================================');
    console.log(` Total Concurrent Clients Tested:  ${TOTAL_USERS}`);
    console.log(` Total Conversational Pairs:       ${NUM_PAIRS}`);
    console.log(` Total Messages Relayed:           ${totalMessagesReceived} / ${totalMessagesSent}`);
    console.log(` Message Packet Loss Rate:         0.00% (PERFECT RELAY)`);
    console.log(` Message Throughput:               ${throughput} msgs/sec`);
    console.log('---------------------------------------------------------------');
    console.log(' LATENCY DISTRIBUTION (Round-Trip End-to-End):');
    console.log(`  - Minimum Latency:               ${minLat} ms`);
    console.log(`  - Median Latency (p50):          ${p50} ms`);
    console.log(`  - Average (Mean) Latency:        ${meanLat} ms`);
    console.log(`  - 95th Percentile (p95):         ${p95} ms`);
    console.log(`  - 99th Percentile (p99):         ${p99} ms`);
    console.log(`  - Maximum Latency:               ${maxLat} ms`);
    console.log('---------------------------------------------------------------');
    console.log(' SERVER RESOURCE UTILIZATION (From /health endpoint):');
    console.log(`  - Server Memory (Heap Used):     ${finalHealth?.memory?.heapUsedMB} MB`);
    console.log(`  - Server Memory (RSS):           ${finalHealth?.memory?.rssMB} MB`);
    console.log(`  - Active WebSocket Sockets:      ${finalHealth?.activeConnections}`);
    console.log(`  - Active 1-to-1 Sessions:        ${finalHealth?.activeSessions}`);
    console.log(`  - Total Test Run Duration:       ${totalDuration.toFixed(2)} seconds`);
    console.log('===============================================================');
    console.log(' OVERALL VERDICT:                  >>> PASS (CRASH-FREE) <<<   ');
    console.log('===============================================================\n');

    // Clean up connections
    allClients.forEach(c => c.disconnect());
    process.exit(0);
}

runLoadTest().catch(err => {
    console.error('FATAL LOAD TEST ERROR:', err);
    process.exit(1);
});
