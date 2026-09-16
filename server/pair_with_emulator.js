const { WebSocket } = require('ws');
const crypto = require('crypto');

const code = '797210';
const ws = new WebSocket('ws://localhost:8088');

const deviceB_keys = crypto.generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
    publicKeyEncoding: { type: 'spki', format: 'der' },
    privateKeyEncoding: { type: 'pkcs8', format: 'der' }
});
const deviceB_id = crypto.createHash('sha256').update(deviceB_keys.publicKey).digest('hex').substring(0, 16);

ws.on('open', () => {
    console.log('[Device B Sim] Connected. Joining code:', code);
    ws.send(JSON.stringify({
        type: 'JOIN_PAIRING_CODE',
        code: code,
        deviceId: deviceB_id
    }));
});

ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());
    console.log('[Device B Sim] Received message:', msg.type);

    if (msg.type === 'PAIRING_ACCEPTED') {
        console.log('[Device B Sim] Pairing accepted. Waiting for OFFER_KEY from emulator...');
    }

    if (msg.type === 'PAIR_HANDSHAKE') {
        const payload = JSON.parse(msg.payload);
        console.log('[Device B Sim] Handshake action:', payload.action);

        if (payload.action === 'OFFER_KEY') {
            console.log('[Device B Sim] Sending ANSWER_KEY to emulator...');
            ws.send(JSON.stringify({
                type: 'PAIR_HANDSHAKE',
                payload: JSON.stringify({
                    action: 'ANSWER_KEY',
                    publicKey: deviceB_keys.publicKey.toString('base64'),
                    deviceId: deviceB_id
                })
            }));

            // Compute secret and SAS
            const privKey = crypto.createPrivateKey({ key: deviceB_keys.privateKey, format: 'der', type: 'pkcs8' });
            const pubKey = crypto.createPublicKey({ key: Buffer.from(payload.publicKey, 'base64'), format: 'der', type: 'spki' });
            const secret = crypto.diffieHellman({ privateKey: privKey, publicKey: pubKey });

            // HKDF-SHA256 for SAS
            const combinedPubs = Buffer.concat([Buffer.from(payload.publicKey, 'base64'), deviceB_keys.publicKey]);
            const prk = crypto.createHmac('sha256', Buffer.alloc(32, 0)).update(secret).digest();
            const hmac = crypto.createHmac('sha256', prk);
            hmac.update(combinedPubs);
            hmac.update(Buffer.from('privatetwo-sas-v1', 'utf8'));
            hmac.update(Buffer.from([0x01]));
            const okm = hmac.digest();

            const num = Math.abs(okm.readInt32BE(0)) % 100000000;
            const str = num.toString().padStart(8, '0');
            const sas = `${str.substring(0, 4)}-${str.substring(4, 8)}`;

            console.log('⭐⭐⭐ [Device B Sim] Computed SAS Code:', sas);
            console.log('[Device B Sim] Check the emulator screen now! It should display this EXACT SAS code!');
        }
    }
});
