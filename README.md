# PrivateTwo: Strictly Private 1-to-1 Android Communication

PrivateTwo is a production-oriented, privacy-first Android application designed exclusively for authenticated, end-to-end encrypted communication between **exactly two paired devices**.

It supports 1-to-1 text chat, photo sharing, chunked file transfers, and 1-to-1 WebRTC audio and video calling with **zero permanent server-side storage** of messages, media, or private keys.

---

## Key Features

1. **Strict 2-Device Limit**: Enforces a hard boundary of exactly two paired devices. Third devices, group chats, public profiles, and discoverability are strictly prohibited.
2. **Cryptographic Pairing (No QR Codes)**: Manual, human-verifiable pairing using single-use 6-digit numeric rendezvous codes combined with an 8-digit **Short Authentication String (SAS)** derived from mutual ECDH key agreement.
3. **End-to-End Encryption (E2EE)**:
   - Asymmetric: NIST P-256 (secp256r1) EC KeyPair with private key protection via `AndroidKeyStore`.
   - Key Agreement: ECDH with RFC 5869 HKDF-SHA-256 for domain-separated inbound and outbound keys.
   - Symmetric Cipher: AES-256-GCM AEAD with 12-byte cryptographically secure IVs and 128-bit authentication tags.
   - Replay Protection: Monotonic sequence validation, timestamp freshness bounds, and duplicate message ID filtering.
4. **WebRTC Real-Time Calling**:
   - 1-to-1 encrypted Audio and Video calling.
   - Hardware-accelerated video capture (Camera2 API) with front/back camera switching and mute.
   - Audio routing with speakerphone and acoustic echo cancellation.
   - Peer-to-peer (P2P) RTC DataChannel for chat and file chunks when direct connectivity is available.
5. **Chunked File & Photo Sharing**:
   - 16 KB chunked streaming over secure data channel or signaling fallback.
   - Pre-computed SHA-256 integrity verification.
   - Path traversal protection sanitizing received filenames.
6. **Privacy Controls**:
   - `FLAG_SECURE` screen shielding against screenshots and recent-apps preview.
   - Biometric / Device credential app lock (BiometricPrompt).
   - Stealth notifications (generic "New private message", zero content preview).
   - Immediate cryptographic wipe on "Unpair Device".

---

## Architecture Overview

```
app/src/main/java/org/privatetwo/app/
├── core/
│   ├── crypto/         # NIST P-256, ECDH, HKDF-SHA256, AES-256-GCM, SAS, Envelope
│   ├── database/       # Room database (Messages, Transfers, CallRecords)
│   ├── security/       # AndroidKeyStore, SecureStorage, BiometricAuthHelper
│   ├── signaling/      # WebSocket client for ephemeral SDP/ICE/Pairing relay
│   └── webrtc/         # WebRtcSessionManager, PeerConnection, Camera2, Audio/Video tracks
├── feature/
│   ├── calls/          # CallScreen, IncomingCallScreen, CallViewModel
│   ├── chat/           # ChatScreen, ChatViewModel, ChatRepository
│   ├── files/          # FileTransferManager (chunking, SHA-256, path sanitization)
│   ├── pairing/        # PairingScreen, PairingViewModel, PairingManager
│   └── settings/       # PrivacySettingsScreen (FLAG_SECURE, Biometrics, Unpair)
├── MainActivity.kt     # Jetpack Compose Navigation & Lifecycle
└── PrivateTwoApp.kt    # Application singleton and notification channel setup
```

---

## Technical Specifications & Requirements

- **Android Studio**: Iguana (2023.2.1) or Ladybug (2024.2.1+)
- **JDK**: Java 17 LTS (Oracle JDK or OpenJDK 17)
- **Android Gradle Plugin (AGP)**: 8.5.2
- **Gradle**: 8.9
- **Kotlin**: 1.9.24 (Compose Compiler 1.5.14)
- **Compile SDK**: 34 (Android 14) / 35 (Android 15)
- **Target SDK**: 34
- **Min SDK**: 26 (Android 8.0 Oreo)

---

## Getting Started & Build Instructions

### 1. Configure Environment
Copy the example property templates:
```bash
cp local.properties.example local.properties
cp gradle.properties.example gradle.properties
```

Edit `local.properties` to specify your Android SDK path and optional STUN/TURN servers:
```properties
sdk.dir=C:\\Users\\<username>\\AppData\\Local\\Android\\Sdk
privatetwo.stun.server=stun:stun.l.google.com:19302
privatetwo.signaling.url=ws://10.0.2.2:8080
```

### 2. Build Debug APK
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat assembleDebug
```
The output APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

### 3. Build Release APK
To build an optimized release APK:
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat assembleRelease
```
To sign the release build, set the environment variables or properties:
- `RELEASE_STORE_FILE`: Path to your keystore `.jks` file
- `RELEASE_STORE_PASSWORD`: Keystore password
- `RELEASE_KEY_ALIAS`: Key alias
- `RELEASE_KEY_PASSWORD`: Key password

---

## Running the Automated Test Suite

PrivateTwo includes unit and security tests covering key generation, ECDH agreement, HKDF derivation, AES-GCM AEAD, replay attack rejection, file chunking integrity, and path traversal protection.

Run all unit tests:
```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
.\gradlew.bat testDebugUnitTest
```

---

## Pairing Guide (Step-by-Step)

1. Open PrivateTwo on **Device A** and tap **Generate Pairing Code**.
   - A 6-digit temporary code is displayed (e.g. `849 201`) with a 3-minute countdown timer.
2. Open PrivateTwo on **Device B**, enter `849 201`, and tap **Pair With Device**.
3. Both devices exchange their EC P-256 public keys via the ephemeral signaling server and derive session keys.
4. **Security Check (SAS)**:
   - Both screens simultaneously display an identical 8-digit Short Authentication String, e.g. `8471-2294`.
   - Users compare the numbers on both screens.
   - Tap **Confirm Match** on both devices.
5. The devices are now securely paired. The signaling server discards the rendezvous session from memory.

---

## Running the Ephemeral Signaling Server

A zero-retention Node.js WebSocket signaling server is included in `server/`:
```bash
cd server
npm install
npm start
```
Or run with Docker:
```bash
docker build -t privatetwo-signaling server/
docker run -p 8080:8080 privatetwo-signaling
```

---

## Security Model & Guarantees

For complete details on threat modeling, cryptographic primitives, and security limitations, see [SECURITY.md](SECURITY.md).
