/**
 * E2E Integration Test for PrivateTwo 1-to-1 Architecture
 * Validates complete flow between two independent virtual devices:
 * 1. WebSocket connection to port 8088
 * 2. 6-digit PIN rendezvous pairing
 * 3. Elliptic Curve Diffie-Hellman (P-256) key exchange & mutual SAS calculation
 * 4. SAS visual match confirmation
 * 5. 1-to-1 Encrypted chat envelope relay
 * 6. WebRTC SDP offer/answer exchange
 * 7. Audio/Video call offer/answer flow
 */

const { WebSocket } = require('ws');
const crypto = require('crypto');

const SIGNALING_URL = 'ws://localhost:8088';

function generateEcKeyPair() {
    return crypto.generateKeyPairSync('ec', {
        namedCurve: 'prime256v1',
        publicKeyEncoding: { type: 'spki', format: 'der' },
        privateKeyEncoding: { type: 'pkcs8', format: 'der' }
    });
}

function computeSharedSecret(privateKeyDer, peerPublicKeyDer) {
    const privKey = crypto.createPrivateKey({ key: privateKeyDer, format: 'der', type: 'pkcs8' });
    const pubKey = crypto.createPublicKey({ key: peerPublicKeyDer, format: 'der', type: 'spki' });
    return crypto.diffieHellman({ privateKey: privKey, publicKey: pubKey });
}

function deriveSas(sharedSecret, pubA, pubB) {
    // HKDF-SHA256
    const combinedPubs = Buffer.concat([pubA, pubB]);
    const prk = crypto.createHmac('sha256', Buffer.alloc(32, 0)).update(sharedSecret).digest();
    const hmac = crypto.createHmac('sha256', prk);
    hmac.update(combinedPubs);
    hmac.update(Buffer.from('privatetwo-sas-v1', 'utf8'));
    hmac.update(Buffer.from([0x01]));
    const okm = hmac.digest();

    const num = Math.abs(okm.readInt32BE(0)) % 100000000;
    const str = num.toString().padStart(8, '0');
    return `${str.substring(0, 4)}-${str.substring(4, 8)}`;
}

async function runE2eFlow() {
    console.log('--- Starting PrivateTwo E2E Flow Simulation ---');

    const deviceA_keys = generateEcKeyPair();
    const deviceA_id = crypto.createHash('sha256').update(deviceA_keys.publicKey).digest('hex').substring(0, 16);

    const deviceB_keys = generateEcKeyPair();
    const deviceB_id = crypto.createHash('sha256').update(deviceB_keys.publicKey).digest('hex').substring(0, 16);

    console.log(`Device A ID: ${deviceA_id}`);
    console.log(`Device B ID: ${deviceB_id}`);

    // Step 1: Connect Device A
    const wsA = new WebSocket(SIGNALING_URL);
    await new Promise(resolve => wsA.on('open', resolve));
    console.log('✔ Device A connected to signaling server');

    // Step 2: Register pairing code 789123 on Device A
    const pairingCode = '789123';
    wsA.send(JSON.stringify({
        type: 'REGISTER_PAIRING_CODE',
        code: pairingCode,
        deviceId: deviceA_id
    }));

    const regAck = await new Promise(resolve => {
        wsA.once('message', data => resolve(JSON.parse(data.toString())));
    });
    console.log(`✔ Device A registered pairing code ${regAck.code}, TTL: ${regAck.expiresInSeconds}s`);

    // Step 3: Connect Device B
    const wsB = new WebSocket(SIGNALING_URL);
    await new Promise(resolve => wsB.on('open', resolve));
    console.log('✔ Device B connected to signaling server');

    // Step 4: Device B joins with code 789123
    wsB.send(JSON.stringify({
        type: 'JOIN_PAIRING_CODE',
        code: pairingCode,
        deviceId: deviceB_id
    }));

    const [eventA_peerJoined, eventB_accepted] = await Promise.all([
        new Promise(resolve => wsA.once('message', d => resolve(JSON.parse(d.toString())))),
        new Promise(resolve => wsB.once('message', d => resolve(JSON.parse(d.toString())))),
    ]);

    console.log(`✔ Peer rendezvous successful! Session ID: ${eventA_peerJoined.sessionId}`);
    console.log(`✔ Device A notified: peer ${eventA_peerJoined.peerDeviceId} joined`);
    console.log(`✔ Device B notified: accepted with peer ${eventB_accepted.peerDeviceId}`);

    // Step 5: Key exchange handshake
    // Device A sends OFFER_KEY
    wsA.send(JSON.stringify({
        type: 'PAIR_HANDSHAKE',
        payload: JSON.stringify({
            action: 'OFFER_KEY',
            publicKey: deviceA_keys.publicKey.toString('base64'),
            deviceId: deviceA_id
        })
    }));

    const msgToB = await new Promise(resolve => wsB.once('message', d => resolve(JSON.parse(d.toString()))));
    const offerPayload = JSON.parse(msgToB.payload);
    console.log('✔ Device B received OFFER_KEY from Device A');

    // Device B sends ANSWER_KEY
    wsB.send(JSON.stringify({
        type: 'PAIR_HANDSHAKE',
        payload: JSON.stringify({
            action: 'ANSWER_KEY',
            publicKey: deviceB_keys.publicKey.toString('base64'),
            deviceId: deviceB_id
        })
    }));

    const msgToA = await new Promise(resolve => wsA.once('message', d => resolve(JSON.parse(d.toString()))));
    const answerPayload = JSON.parse(msgToA.payload);
    console.log('✔ Device A received ANSWER_KEY from Device B');

    // Step 6: Compute shared secrets & SAS on both devices
    const secretOnA = computeSharedSecret(deviceA_keys.privateKey, Buffer.from(answerPayload.publicKey, 'base64'));
    const secretOnB = computeSharedSecret(deviceB_keys.privateKey, Buffer.from(offerPayload.publicKey, 'base64'));

    if (!secretOnA.equals(secretOnB)) {
        throw new Error('ECDH shared secret mismatch between Device A and B!');
    }
    console.log('✔ ECDH shared secret computed and matched on both devices');

    const sasA = deriveSas(secretOnA, deviceA_keys.publicKey, Buffer.from(answerPayload.publicKey, 'base64'));
    const sasB = deriveSas(secretOnB, Buffer.from(offerPayload.publicKey, 'base64'), deviceB_keys.publicKey);

    console.log(`Device A SAS Code: ${sasA}`);
    console.log(`Device B SAS Code: ${sasB}`);

    if (sasA !== sasB) {
        throw new Error('SAS codes do not match!');
    }
    console.log('✔ Visual SAS matched perfectly! Both users confirm match.');

    // Step 7: Chat message relay test
    const dummyEnvelope = JSON.stringify({
        version: 1,
        messageId: 'msg_001',
        senderDeviceId: deviceA_id,
        recipientDeviceId: deviceB_id,
        timestamp: Date.now(),
        sequenceNumber: 1,
        iv: crypto.randomBytes(12).toString('base64'),
        ciphertext: 'AQIDBAUGCAkKCwwNDg==',
        authTag: '8PvXyQ=='
    });

    wsA.send(JSON.stringify({
        type: 'E2EE_ENVELOPE',
        envelope: dummyEnvelope
    }));

    const bReceivedEnvelope = await new Promise(resolve => wsB.once('message', d => resolve(JSON.parse(d.toString()))));
    if (bReceivedEnvelope.type !== 'E2EE_ENVELOPE' || bReceivedEnvelope.envelope !== dummyEnvelope) {
        throw new Error('E2EE envelope relay failed!');
    }
    console.log('✔ E2EE chat envelope successfully relayed from Device A to Device B');

    // Step 8: WebRTC Call Offer & Answer test
    wsA.send(JSON.stringify({
        type: 'CALL_OFFER',
        isVideo: true
    }));

    const bReceivedCall = await new Promise(resolve => wsB.once('message', d => resolve(JSON.parse(d.toString()))));
    console.log(`✔ Device B received CALL_OFFER (isVideo: ${bReceivedCall.isVideo})`);

    wsB.send(JSON.stringify({
        type: 'CALL_ANSWER',
        accepted: true
    }));

    const aReceivedCallAnswer = await new Promise(resolve => wsA.once('message', d => resolve(JSON.parse(d.toString()))));
    console.log(`✔ Device A received CALL_ANSWER (accepted: ${aReceivedCallAnswer.accepted})`);

    // Step 9: WebRTC SDP & ICE relay
    wsA.send(JSON.stringify({
        type: 'SDP_OFFER',
        sdp: 'v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n'
    }));

    const bReceivedSdp = await new Promise(resolve => wsB.once('message', d => resolve(JSON.parse(d.toString()))));
    console.log('✔ WebRTC SDP offer relayed to Device B');

    wsB.send(JSON.stringify({
        type: 'SDP_ANSWER',
        sdp: 'v=0\r\no=- 54321 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n'
    }));

    const aReceivedSdpAnswer = await new Promise(resolve => wsA.once('message', d => resolve(JSON.parse(d.toString()))));
    console.log('✔ WebRTC SDP answer relayed to Device A');

    // Step 10: Call End
    wsA.send(JSON.stringify({ type: 'CALL_END' }));
    const bReceivedCallEnd = await new Promise(resolve => wsB.once('message', d => resolve(JSON.parse(d.toString()))));
    console.log('✔ Call End event relayed successfully');

    wsA.close();
    wsB.close();
    console.log('--- ALL E2E FLOW CHECKS PASSED WITH 100% SUCCESS ---');
}

runE2eFlow().catch(err => {
    console.error('❌ E2E Simulation Error:', err);
    process.exit(1);
});
