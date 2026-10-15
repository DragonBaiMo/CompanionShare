// Copyright (c) 2026 CompanionShare. MIT License.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../src/server.mjs';
import { randomToken, tokenMatches } from '../src/store.mjs';
import { makeIceServers } from '../src/turn.mjs';

const ENROLLMENT_KEY = randomToken();

class Client {
  constructor(socket) {
    this.socket = socket;
    this.queue = [];
    this.waiters = [];
    socket.addEventListener('message', (evt) => {
      const data = JSON.parse(evt.data);
      this.queue.push(data);
      for (const w of this.waiters.splice(0)) w();
    });
  }
  send(type, extra = {}) { this.socket.send(JSON.stringify({ type, ...extra })); }
  async next(type, timeout = 2000) {
    const pick = () => this.queue.findIndex((m) => m.type === type);
    if (pick() < 0) await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`missing ${type}: ${JSON.stringify(this.queue)}`)), timeout);
      const check = () => { if (pick() >= 0) { clearTimeout(timer); resolve(); } else this.waiters.push(check); };
      this.waiters.push(check);
    });
    return this.queue.splice(pick(), 1)[0];
  }
  close() { this.socket.close(); }
}

async function connect(app) {
  const port = app.server.address().port;
  const socket = new WebSocket(`ws://127.0.0.1:${port}/ws`);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, { once: true });
    socket.addEventListener('error', reject, { once: true });
  });
  return new Client(socket);
}

async function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'companion-test-'));
  const app = createApp({ dataFile: join(dir, 'devices.json'), port: 0,
    enrollmentKey: ENROLLMENT_KEY });
  await app.start();
  t.after(async () => { await app.close(); rmSync(dir, { force: true, recursive: true }); });
  return { app, dir };
}

async function pair(t) {
  const { app, dir } = await fixture(t);
  const owner = await connect(app);
  const viewer = await connect(app);
  const id = randomToken(16);
  const secret = randomToken();
  owner.send('owner.register', { deviceId: id, secret, enrollmentKey: ENROLLMENT_KEY });
  assert.equal((await owner.next('owner.ready')).paired, false);
  owner.send('owner.pair.create');
  const code = (await owner.next('pair.code')).code;
  assert.match(code, /^\d{8}$/);
  viewer.send('viewer.pair', { code });
  assert.equal((await viewer.next('pair.waiting')).type, 'pair.waiting');
  const requestId = (await owner.next('pair.request')).requestId;
  owner.send('owner.pair.approve', { requestId });
  const accepted = await viewer.next('pair.accepted');
  assert.equal(accepted.deviceId, id);
  assert.equal((await viewer.next('viewer.ready')).armed, false);
  assert.equal((await owner.next('pair.completed')).type, 'pair.completed');
  const stored = JSON.parse(readFileSync(join(dir, 'devices.json'), 'utf8'));
  assert.ok(!JSON.stringify(stored).includes(accepted.token));
  assert.ok(tokenMatches(accepted.token, stored.devices[id].viewerHash));
  return { app, owner, viewer, id, secret, token: accepted.token };
}

test('pair requires owner confirmation, binds exactly one viewer and stores only token hashes', async (t) => {
  const { app, owner, viewer, id, secret, token } = await pair(t);
  viewer.send('owner.arm', { armed: true });
  assert.equal((await viewer.next('error')).code, 'UNKNOWN_TYPE');
  const impostor = await connect(app);
  impostor.send('viewer.login', { deviceId: id, token: randomToken() });
  assert.equal((await impostor.next('error')).code, 'DENIED');
  impostor.send('owner.register', { deviceId: id, secret: randomToken() });
  assert.equal((await impostor.next('error')).code, 'DENIED');
  const replacement = await connect(app);
  replacement.send('viewer.login', { deviceId: id, token });
  assert.equal((await replacement.next('viewer.ready')).type, 'viewer.ready');
  assert.equal(app.relay.viewers.get(id).identity.role, 'viewer');
  assert.equal(app.relay.viewers.get(id).closed, false);
  owner.close(); viewer.close(); impostor.close(); replacement.close();
});

test('only armed owner can allow session; signaling is role-limited; pause revokes active session', async (t) => {
  const { owner, viewer } = await pair(t);
  viewer.send('viewer.session.request', { source: 'front', audio: true });
  assert.equal((await viewer.next('error')).code, 'OWNER_UNAVAILABLE');
  owner.send('owner.arm', { armed: true });
  await owner.next('owner.armed');
  await viewer.next('owner.status');
  viewer.send('viewer.session.request', { source: 'front', audio: true });
  const req = await owner.next('session.request');
  assert.equal(req.source, 'front');
  owner.send('owner.session.accept', { requestId: req.requestId });
  await viewer.next('session.accepted');
  viewer.send('viewer.session.ready', { requestId: req.requestId });
  await owner.next('session.started');
  viewer.send('signal.offer', { requestId: req.requestId, sdp: 'x' });
  assert.equal((await viewer.next('error')).code, 'FORBIDDEN');
  owner.send('signal.offer', { requestId: req.requestId, sdp: 'v=0\r\n' });
  assert.equal((await viewer.next('signal.offer')).sdp, 'v=0\r\n');
  viewer.send('signal.answer', { requestId: req.requestId, sdp: 'v=0\r\n' });
  await owner.next('signal.answer');
  owner.send('chat.send', { text: 'hello' });
  assert.equal((await viewer.next('chat.message')).text, 'hello');
  owner.send('owner.arm', { armed: false });
  await owner.next('owner.armed');
  await viewer.next('session.ended');
  viewer.send('signal.answer', { requestId: req.requestId, sdp: 'forbidden' });
  assert.equal((await viewer.next('error')).code, 'NO_ACTIVE_SESSION');
});

test('pair code is single use; unpair cuts access and prevents old viewer login', async (t) => {
  const { app, owner, viewer, id, token } = await pair(t);
  owner.send('owner.unpair');
  await owner.next('owner.unpaired');
  await viewer.next('viewer.revoked');
  const stale = await connect(app);
  stale.send('viewer.login', { deviceId: id, token });
  assert.equal((await stale.next('error')).code, 'DENIED');
});

test('pairing denial and malformed requests do not grant media access', async (t) => {
  const { app } = await fixture(t);
  const outsider = await connect(app);
  outsider.send('viewer.pair', { code: '00000000' });
  assert.equal((await outsider.next('error')).code, 'INVALID_PAIR_CODE');
  outsider.send('viewer.session.request', { source: 'screen', audio: true });
  assert.equal((await outsider.next('error')).code, 'UNAUTHENTICATED');
  outsider.socket.send('not json');
  assert.equal((await outsider.next('error')).code, 'INVALID_JSON');
});

test('TURN credentials generated only when configured, with 1h expiry', () => {
  const none = makeIceServers({}, 'device', 1_000_000);
  assert.equal(none.length, 1);
  const ice = makeIceServers({ turnHost: 'turn.example.org', turnSecret: 'test-secret' }, 'device', 1_000_000);
  assert.equal(ice.length, 2);
  assert.equal(ice[0].urls[0], 'stun:turn.example.org:3478');
  assert.equal(ice[1].username, '4600:device');
  assert.ok(ice[1].credential);
});

test('plaintext public binding is disallowed by default', () => {
  assert.throws(() => createApp({ bind: '0.0.0.0', port: 0,
    enrollmentKey: ENROLLMENT_KEY }), /plaintext public bind forbidden/);
});

test('server enrollment key is required for first-time owner onboarding', async (t) => {
  const { app } = await fixture(t);
  const owner = await connect(app);
  const id = randomToken(16);
  const secret = randomToken();
  owner.send('owner.register', { deviceId: id, secret });
  assert.equal((await owner.next('error')).code, 'ENROLLMENT_DENIED');
  owner.send('owner.register', { deviceId: id, secret, enrollmentKey: randomToken() });
  assert.equal((await owner.next('error')).code, 'ENROLLMENT_DENIED');
  owner.send('owner.register', { deviceId: id, secret, enrollmentKey: ENROLLMENT_KEY });
  await owner.next('owner.ready');
  owner.close();
});

test('screen stream request waits for explicit Owner session approval', async (t) => {
  const { app, owner, viewer } = await pair(t);
  owner.send('owner.arm', { armed: true });
  await owner.next('owner.armed');
  viewer.send('viewer.session.request', { source: 'screen', audio: false });
  const waiting = await owner.next('session.request');
  assert.equal(waiting.source, 'screen');
  assert.equal(app.relay.active.size, 0);
  owner.send('owner.session.reject', { requestId: waiting.requestId });
  assert.equal((await viewer.next('session.rejected')).reason, 'OWNER_REJECTED');
  assert.equal(app.relay.active.size, 0);
});

test('a captured pairing code is invalid after successful one-time use', async (t) => {
  const { app } = await fixture(t);
  const owner = await connect(app);
  const id = randomToken(16);
  owner.send('owner.register', { deviceId: id, secret: randomToken(), enrollmentKey: ENROLLMENT_KEY });
  await owner.next('owner.ready');
  owner.send('owner.pair.create');
  const code = (await owner.next('pair.code')).code;
  const viewer = await connect(app);
  viewer.send('viewer.pair', { code });
  const request = await owner.next('pair.request');
  owner.send('owner.pair.approve', { requestId: request.requestId });
  await viewer.next('pair.accepted');
  const stranger = await connect(app);
  stranger.send('viewer.pair', { code });
  assert.equal((await stranger.next('error')).code, 'INVALID_PAIR_CODE');
});

test('WebSocket large SDP messages use RFC6455 64-bit length frames', async (t) => {
  const { owner, viewer } = await pair(t);
  owner.send('owner.arm', { armed: true });
  await owner.next('owner.armed');
  viewer.send('viewer.session.request', { source: 'back', audio: true });
  const incoming = await owner.next('session.request');
  owner.send('owner.session.accept', { requestId: incoming.requestId });
  await viewer.next('session.accepted');
  viewer.send('viewer.session.ready', { requestId: incoming.requestId });
  await owner.next('session.started');
  const largeSdp = 'v='.padEnd(90_000, 'a');
  owner.send('signal.offer', { requestId: incoming.requestId, sdp: largeSdp });
  assert.equal((await viewer.next('signal.offer')).sdp.length, 90_000);
});

test('media offer waits for the Viewer renderer and peer to report readiness', async (t) => {
  const { app, owner, viewer } = await pair(t);
  owner.send('owner.arm', { armed: true });
  await owner.next('owner.armed');
  viewer.send('viewer.session.request', { source: 'front', audio: true });
  const incoming = await owner.next('session.request');
  owner.send('owner.session.accept', { requestId: incoming.requestId });
  await viewer.next('session.accepted');
  owner.send('signal.offer', { requestId: incoming.requestId, sdp: 'v=0\r\n' });
  assert.equal((await owner.next('error')).code, 'NO_ACTIVE_SESSION');
  viewer.send('viewer.session.ready', { requestId: 'stale' });
  assert.equal((await viewer.next('error')).code, 'NO_ACTIVE_SESSION');
  assert.equal([...app.relay.active.values()][0].viewerReady, false);
  viewer.send('viewer.session.ready', { requestId: incoming.requestId });
  assert.equal((await owner.next('session.started')).requestId, incoming.requestId);
  viewer.send('viewer.session.ready', { requestId: incoming.requestId });
  owner.send('signal.offer', { requestId: incoming.requestId, sdp: 'v=0\r\n' });
  assert.equal((await viewer.next('signal.offer')).sdp, 'v=0\r\n');
});

test('an accepted session without a Viewer ready signal times out', async (t) => {
  const { app, owner, viewer } = await pair(t);
  owner.send('owner.arm', { armed: true });
  await owner.next('owner.armed');
  viewer.send('viewer.session.request', { source: 'back', audio: false });
  const incoming = await owner.next('session.request');
  owner.send('owner.session.accept', { requestId: incoming.requestId });
  await viewer.next('session.accepted');
  app.relay.cleanup(Date.now() + 120_000);
  assert.equal((await owner.next('session.ended')).reason, 'VIEWER_INIT_TIMEOUT');
  assert.equal((await viewer.next('session.ended')).reason, 'VIEWER_INIT_TIMEOUT');
  assert.equal(app.relay.active.size, 0);
});

test('stale session signaling is rejected after switching video source', async (t) => {
  const { owner, viewer } = await pair(t);
  owner.send('owner.arm', { armed: true });
  await owner.next('owner.armed');
  viewer.send('viewer.session.request', { source: 'front', audio: false });
  const first = await owner.next('session.request');
  owner.send('owner.session.accept', { requestId: first.requestId });
  await viewer.next('session.accepted');
  viewer.send('viewer.session.ready', { requestId: first.requestId });
  await owner.next('session.started');
  viewer.send('viewer.session.request', { source: 'back', audio: true });
  await viewer.next('session.ended');
  const second = await owner.next('session.request');
  assert.notEqual(first.requestId, second.requestId);
  owner.send('signal.offer', { requestId: first.requestId, sdp: 'v=0\r\n' });
  assert.equal((await owner.next('error')).code, 'NO_ACTIVE_SESSION');
  owner.send('owner.session.accept', { requestId: second.requestId });
  await viewer.next('session.accepted');
  viewer.send('viewer.session.ready', { requestId: first.requestId });
  assert.equal((await viewer.next('error')).code, 'NO_ACTIVE_SESSION');
  viewer.send('viewer.session.ready', { requestId: second.requestId });
  assert.equal((await owner.next('session.started')).requestId, second.requestId);
});

test('malformed ICE candidates and oversized SDP never reach the other peer', async (t) => {
  const { owner, viewer } = await pair(t);
  owner.send('owner.arm', { armed: true });
  await owner.next('owner.armed');
  viewer.send('viewer.session.request', { source: 'back', audio: true });
  const req = await owner.next('session.request');
  owner.send('owner.session.accept', { requestId: req.requestId });
  await viewer.next('session.accepted');
  viewer.send('viewer.session.ready', { requestId: req.requestId });
  await owner.next('session.started');
  owner.send('signal.ice', { requestId: req.requestId, candidate: '',
    sdpMid: '0', sdpMLineIndex: -1 });
  assert.equal((await owner.next('error')).code, 'INVALID_ICE');
  owner.send('signal.offer', { requestId: req.requestId, sdp: 'a'.repeat(200_001) });
  assert.equal((await owner.next('error')).code, 'INVALID_SDP');
});

test('an expired pairing cannot be approved even before periodic cleanup', async (t) => {
  const { app, owner, id, token } = await pair(t);
  owner.send('owner.pair.create');
  const code = (await owner.next('pair.code')).code;
  const replacement = await connect(app);
  replacement.send('viewer.pair', { code });
  await replacement.next('pair.waiting');
  const request = await owner.next('pair.request');
  app.relay.pairings.get(id).expires = Date.now() - 1;
  owner.send('owner.pair.approve', { requestId: request.requestId });
  assert.equal((await owner.next('error')).code, 'UNKNOWN_PAIR_REQUEST');
  await replacement.next('pair.expired');
  assert.equal(tokenMatches(token, app.relay.store.get(id).viewerHash), true);
});

test('unanswered media request expires and late approval is rejected', async (t) => {
  const { app, owner, viewer, id } = await pair(t);
  owner.send('owner.arm', { armed: true });
  await owner.next('owner.armed');
  viewer.send('viewer.session.request', { source: 'front', audio: true });
  const request = await owner.next('session.request');
  app.relay.cleanup(Date.now() + 61_000);
  assert.equal((await owner.next('session.ended')).reason, 'OWNER_RESPONSE_TIMEOUT');
  assert.equal((await viewer.next('session.ended')).reason, 'OWNER_RESPONSE_TIMEOUT');
  owner.send('owner.session.accept', { requestId: request.requestId });
  assert.equal((await owner.next('error')).code, 'NO_PENDING_SESSION');
  assert.equal(app.relay.pending.size, 0);

  viewer.send('viewer.session.request', { source: 'back', audio: false });
  const second = await owner.next('session.request');
  app.relay.pending.get(id).createdAt = Date.now() - 61_000;
  owner.send('owner.session.accept', { requestId: second.requestId });
  assert.equal((await owner.next('error')).code, 'NO_PENDING_SESSION');
  assert.equal((await viewer.next('session.ended')).reason, 'OWNER_RESPONSE_TIMEOUT');
  assert.equal(app.relay.active.size, 0);
});
