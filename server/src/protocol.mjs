// Copyright (c) 2026 CompanionShare. MIT License.
// Authenticated one-owner/one-viewer signaling. No media or files pass through this process.
import { randomInt } from 'node:crypto';
import { DeviceStore, hashToken, randomToken, tokenMatches } from './store.mjs';
import { makeIceServers } from './turn.mjs';

const PAIR_TTL_MS = 5 * 60_000;
const OWNER_RESPONSE_TTL_MS = 60_000;
const SESSION_READY_TTL_MS = 60_000;
const ALLOWED_SOURCES = new Set(['front', 'back', 'screen']);
const isToken = (s) => typeof s === 'string' && /^[A-Za-z0-9_-]{40,64}$/.test(s);
const isDeviceId = (s) => typeof s === 'string' && /^[A-Za-z0-9_-]{20,32}$/.test(s);

export class Relay {
  constructor({ dataFile, turnHost = '', turnSecret = '', enrollmentKey, maxDevices = 100 }) {
    this.store = new DeviceStore(dataFile);
    this.turn = { turnHost, turnSecret };
    this.maxDevices = maxDevices;
    this.enrollmentHash = hashToken(enrollmentKey);
    this.owners = new Map();
    this.viewers = new Map();
    this.pairings = new Map();
    this.pending = new Map();
    this.active = new Map();
    this.attempts = new Map();
  }

  send(peer, message) { if (peer && !peer.closed) peer.send(message); }
  error(peer, code) { this.send(peer, { type: 'error', code }); }
  owner(id) { return this.owners.get(id); }
  viewer(id) { return this.viewers.get(id); }

  attach(peer, ip = 'unknown') {
    peer.identity = null;
    peer.limiter = { started: Date.now(), count: 0 };
    peer.ip = ip;
    peer.on('message', (raw) => this.handle(peer, raw));
    peer.on('close', () => this.detach(peer));
    peer.on('error', () => peer.close());
  }

  limited(peer) {
    const now = Date.now();
    if (now - peer.limiter.started > 60_000) peer.limiter = { started: now, count: 0 };
    return ++peer.limiter.count > 240;
  }

  handle(peer, raw) {
    if (this.limited(peer)) return peer.close(1008);
    let msg;
    try { msg = JSON.parse(raw); } catch { return this.error(peer, 'INVALID_JSON'); }
    if (!msg || typeof msg !== 'object' || Array.isArray(msg) || typeof msg.type !== 'string') {
      return this.error(peer, 'INVALID_MESSAGE');
    }
    if (!peer.identity) {
      if (msg.type === 'owner.register') return this.registerOwner(peer, msg);
      if (msg.type === 'viewer.login') return this.loginViewer(peer, msg);
      if (msg.type === 'viewer.pair') return this.pairViewer(peer, msg);
      return this.error(peer, 'UNAUTHENTICATED');
    }
    const { role, deviceId } = peer.identity;
    if (role === 'owner') this.ownerMessage(peer, deviceId, msg);
    else this.viewerMessage(peer, deviceId, msg);
  }

  registerOwner(peer, msg) {
    if (!isDeviceId(msg.deviceId) || !isToken(msg.secret)) return this.error(peer, 'INVALID_CREDENTIALS');
    if (!this.store.get(msg.deviceId) && !tokenMatches(msg.enrollmentKey, this.enrollmentHash)) {
      return this.error(peer, 'ENROLLMENT_DENIED');
    }
    if (!this.store.get(msg.deviceId) && Object.keys(this.store.devices).length >= this.maxDevices) {
      return this.error(peer, 'DEVICE_LIMIT');
    }
    if (!this.store.register(msg.deviceId, msg.secret)) return this.error(peer, 'DENIED');
    const old = this.owner(msg.deviceId);
    if (old && old !== peer) old.close(4001);
    peer.identity = { role: 'owner', deviceId: msg.deviceId, armed: false };
    this.owners.set(msg.deviceId, peer);
    this.endSession(msg.deviceId, 'OWNER_RECONNECTED');
    this.send(peer, { type: 'owner.ready', paired: Boolean(this.store.get(msg.deviceId).viewerHash),
      viewerOnline: Boolean(this.viewer(msg.deviceId)), armed: false });
    this.send(this.viewer(msg.deviceId), { type: 'owner.status', online: true, armed: false });
  }

  loginViewer(peer, msg) {
    if (!isDeviceId(msg.deviceId) || !isToken(msg.token)) return this.error(peer, 'INVALID_CREDENTIALS');
    const device = this.store.get(msg.deviceId);
    if (!device || !tokenMatches(msg.token, device.viewerHash)) return this.error(peer, 'DENIED');
    this.attachViewer(peer, msg.deviceId);
  }

  attachViewer(peer, id) {
    const old = this.viewer(id);
    if (old && old !== peer) old.close(4001);
    peer.identity = { role: 'viewer', deviceId: id };
    this.viewers.set(id, peer);
    this.endSession(id, 'VIEWER_RECONNECTED');
    this.send(peer, { type: 'viewer.ready', deviceId: id, online: Boolean(this.owner(id)),
      armed: Boolean(this.owner(id)?.identity?.armed), iceServers: makeIceServers(this.turn, id) });
    this.send(this.owner(id), { type: 'viewer.status', online: true });
  }

  blockedPairAttempt(peer) {
    const now = Date.now();
    const state = this.attempts.get(peer.ip) ?? { until: now + PAIR_TTL_MS, count: 0 };
    if (now > state.until) { state.until = now + PAIR_TTL_MS; state.count = 0; }
    state.count++;
    this.attempts.set(peer.ip, state);
    return state.count > 5;
  }

  pairViewer(peer, msg) {
    if (this.blockedPairAttempt(peer)) return this.error(peer, 'RATE_LIMITED');
    if (typeof msg.code !== 'string' || !/^\d{8}$/.test(msg.code)) return this.error(peer, 'INVALID_PAIR_CODE');
    const record = [...this.pairings.values()].find((p) => p.code === msg.code && p.expires > Date.now());
    if (!record || record.pending) return this.error(peer, 'INVALID_PAIR_CODE');
    const owner = this.owner(record.deviceId);
    if (!owner) return this.error(peer, 'OWNER_OFFLINE');
    record.pending = { id: randomToken(12), peer };
    this.send(owner, { type: 'pair.request', requestId: record.pending.id });
    this.send(peer, { type: 'pair.waiting' });
  }

  ownerMessage(peer, id, msg) {
    switch (msg.type) {
      case 'owner.pair.create': {
        this.pairings.delete(id);
        let code;
        do { code = String(randomInt(100_000_000)).padStart(8, '0'); }
        while ([...this.pairings.values()].some((p) => p.code === code));
        this.pairings.set(id, { code, deviceId: id, expires: Date.now() + PAIR_TTL_MS, pending: null });
        this.send(peer, { type: 'pair.code', code, expiresSeconds: 300 });
        break;
      }
      case 'owner.pair.approve': {
        const pairing = this.pairings.get(id);
        if (!pairing?.pending || pairing.pending.id !== msg.requestId) return this.error(peer, 'UNKNOWN_PAIR_REQUEST');
        if (pairing.expires <= Date.now()) {
          // NOTE: A late approval must not extend the pairing code's five-minute lifespan.
          this.send(pairing.pending.peer, { type: 'pair.expired' });
          this.pairings.delete(id);
          return this.error(peer, 'UNKNOWN_PAIR_REQUEST');
        }
        const waiting = pairing.pending.peer;
        this.pairings.delete(id);
        if (waiting.closed) return this.error(peer, 'VIEWER_OFFLINE');
        const viewerToken = randomToken();
        this.store.setViewer(id, viewerToken); // Replaces previous viewer authorization atomically.
        const previous = this.viewer(id);
        if (previous) previous.close(4003);
        this.send(waiting, { type: 'pair.accepted', deviceId: id, token: viewerToken });
        this.attachViewer(waiting, id);
        this.send(peer, { type: 'pair.completed' });
        break;
      }
      case 'owner.pair.reject': {
        const pairing = this.pairings.get(id);
        if (!pairing?.pending || pairing.pending.id !== msg.requestId) return;
        this.send(pairing.pending.peer, { type: 'pair.rejected' });
        this.pairings.delete(id);
        break;
      }
      case 'owner.unpair': {
        this.store.setViewer(id, null);
        this.pairings.delete(id);
        this.endSession(id, 'UNPAIRED');
        const viewer = this.viewer(id);
        this.send(viewer, { type: 'viewer.revoked' });
        if (viewer) viewer.close(4003);
        this.send(peer, { type: 'owner.unpaired' });
        break;
      }
      case 'owner.arm': {
        if (typeof msg.armed !== 'boolean') return this.error(peer, 'INVALID_ARM_STATE');
        peer.identity.armed = msg.armed;
        if (!msg.armed) this.endSession(id, 'OWNER_PAUSED');
        this.send(peer, { type: 'owner.armed', armed: msg.armed });
        this.send(this.viewer(id), { type: 'owner.status', online: true, armed: msg.armed });
        break;
      }
      case 'owner.session.accept': {
        const session = this.pending.get(id);
        if (!session || session.requestId !== msg.requestId || !peer.identity.armed || !this.viewer(id)) {
          return this.error(peer, 'NO_PENDING_SESSION');
        }
        if (Date.now() - session.createdAt >= OWNER_RESPONSE_TTL_MS) {
          this.endSession(id, 'OWNER_RESPONSE_TIMEOUT');
          return this.error(peer, 'NO_PENDING_SESSION');
        }
        this.pending.delete(id);
        // NOTE: The Viewer must allocate its renderer and peer connection before
        // the Owner may generate an offer. Cross-socket delivery order is not guaranteed.
        this.active.set(id, { ...session, viewerReady: false, acceptedAt: Date.now() });
        this.send(this.viewer(id), { type: 'session.accepted', requestId: session.requestId,
          source: session.source, audio: session.audio });
        break;
      }
      case 'owner.session.reject': {
        const pending = this.pending.get(id);
        if (!pending || pending.requestId !== msg.requestId) return;
        this.pending.delete(id);
        this.send(this.viewer(id), { type: 'session.rejected', reason: 'OWNER_REJECTED' });
        break;
      }
      case 'signal.offer': case 'signal.answer': case 'signal.ice':
        this.signal(peer, id, msg); break;
      case 'session.end': this.endSession(id, 'OWNER_ENDED'); break;
      case 'chat.send': this.chat(peer, id, msg); break;
      default: this.error(peer, 'UNKNOWN_TYPE');
    }
  }

  viewerMessage(peer, id, msg) {
    switch (msg.type) {
      case 'viewer.session.ready': {
        const session = this.active.get(id);
        if (!session || session.requestId !== msg.requestId) return this.error(peer, 'NO_ACTIVE_SESSION');
        if (session.viewerReady) return;
        session.viewerReady = true;
        this.send(this.owner(id), { type: 'session.started', requestId: session.requestId,
          iceServers: makeIceServers(this.turn, id) });
        break;
      }
      case 'viewer.session.request': {
        const owner = this.owner(id);
        if (!owner || !owner.identity.armed) return this.error(peer, 'OWNER_UNAVAILABLE');
        if (!ALLOWED_SOURCES.has(msg.source) || typeof msg.audio !== 'boolean') return this.error(peer, 'INVALID_SOURCE');
        this.endSession(id, 'SWITCH_SOURCE');
        const session = { requestId: randomToken(12), source: msg.source,
          audio: msg.audio, createdAt: Date.now() };
        this.pending.set(id, session);
        this.send(owner, { type: 'session.request', ...session });
        this.send(peer, { type: 'session.waiting', requestId: session.requestId });
        break;
      }
      case 'signal.offer': case 'signal.answer': case 'signal.ice':
        this.signal(peer, id, msg); break;
      case 'session.end': this.endSession(id, 'VIEWER_ENDED'); break;
      case 'chat.send': this.chat(peer, id, msg); break;
      default: this.error(peer, 'UNKNOWN_TYPE');
    }
  }

  signal(peer, id, msg) {
    const session = this.active.get(id);
    if (!session || !session.viewerReady || msg.requestId !== session.requestId) {
      return this.error(peer, 'NO_ACTIVE_SESSION');
    }
    if (msg.type === 'signal.offer' && peer.identity.role !== 'owner') return this.error(peer, 'FORBIDDEN');
    if (msg.type === 'signal.answer' && peer.identity.role !== 'viewer') return this.error(peer, 'FORBIDDEN');
    if (msg.type === 'signal.offer' || msg.type === 'signal.answer') {
      if (typeof msg.sdp !== 'string' || msg.sdp.length > 200_000) return this.error(peer, 'INVALID_SDP');
    } else if (typeof msg.candidate !== 'string' || msg.candidate.length > 4096
      || typeof msg.sdpMid !== 'string' || msg.sdpMid.length > 32
      || !Number.isInteger(msg.sdpMLineIndex) || msg.sdpMLineIndex < 0 || msg.sdpMLineIndex > 128) {
      return this.error(peer, 'INVALID_ICE');
    }
    const target = peer.identity.role === 'owner' ? this.viewer(id) : this.owner(id);
    this.send(target, { ...msg });
  }

  chat(peer, id, msg) {
    if (typeof msg.text !== 'string' || !msg.text.trim() || msg.text.length > 2000) {
      return this.error(peer, 'INVALID_CHAT');
    }
    const target = peer.identity.role === 'owner' ? this.viewer(id) : this.owner(id);
    if (!target) return this.error(peer, 'PEER_OFFLINE');
    this.send(target, { type: 'chat.message', text: msg.text, from: peer.identity.role });
    this.send(peer, { type: 'chat.sent' });
  }

  endSession(id, reason) {
    const active = this.active.get(id);
    const pending = this.pending.get(id);
    this.active.delete(id);
    this.pending.delete(id);
    if (!active && !pending) return;
    this.send(this.owner(id), { type: 'session.ended', reason });
    this.send(this.viewer(id), { type: 'session.ended', reason });
  }

  detach(peer) {
    if (!peer.identity) {
      for (const [id, record] of this.pairings) {
        if (record.pending?.peer === peer) this.pairings.delete(id);
      }
      return;
    }
    const { deviceId, role } = peer.identity;
    if (role === 'owner' && this.owner(deviceId) === peer) {
      this.owners.delete(deviceId);
      this.pairings.delete(deviceId);
      this.endSession(deviceId, 'OWNER_OFFLINE');
      this.send(this.viewer(deviceId), { type: 'owner.status', online: false, armed: false });
    }
    if (role === 'viewer' && this.viewer(deviceId) === peer) {
      this.viewers.delete(deviceId);
      this.endSession(deviceId, 'VIEWER_OFFLINE');
      this.send(this.owner(deviceId), { type: 'viewer.status', online: false });
    }
  }

  cleanup(now = Date.now()) {
    for (const [ip, state] of this.attempts) {
      if (state.until < now) this.attempts.delete(ip);
    }
    for (const [id, code] of this.pairings) {
      if (code.expires < now) {
        this.send(code.pending?.peer, { type: 'pair.expired' });
        this.pairings.delete(id);
      }
    }
    for (const [id, session] of this.pending) {
      if (now - session.createdAt >= OWNER_RESPONSE_TTL_MS) {
        this.endSession(id, 'OWNER_RESPONSE_TIMEOUT');
      }
    }
    for (const [id, session] of this.active) {
      if (!session.viewerReady && now - session.acceptedAt > SESSION_READY_TTL_MS) {
        this.endSession(id, 'VIEWER_INIT_TIMEOUT');
      }
    }
  }
}
