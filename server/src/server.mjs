// Copyright (c) 2026 CompanionShare. MIT License.
// Single-process, dependency-free signal server; media is end-to-end WebRTC.
import { createServer as createHttpServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
import { Relay } from './protocol.mjs';
import { upgradeWebSocket } from './ws.mjs';

export function createApp(config = {}) {
  const bind = config.bind ?? process.env.BIND ?? '127.0.0.1';
  const port = Number(config.port ?? process.env.PORT ?? 8087);
  const cert = config.tlsCert ?? process.env.TLS_CERT;
  const key = config.tlsKey ?? process.env.TLS_KEY;
  const allowPrivateHttp = config.allowPrivateHttp ?? (process.env.ALLOW_PRIVATE_HTTP === '1');
  const enrollmentKey = config.enrollmentKey ?? process.env.ENROLLMENT_KEY;
  if (typeof enrollmentKey !== 'string' || !/^[A-Za-z0-9_-]{40,64}$/.test(enrollmentKey)) {
    throw Error('ENROLLMENT_KEY must be 32+ bytes of base64url entropy');
  }
  if (!Number.isInteger(port) || port < 0 || port > 65535) throw Error('invalid port');
  if (Boolean(cert) !== Boolean(key)) throw Error('both TLS_CERT and TLS_KEY are required');
  if (bind !== '127.0.0.1' && bind !== '::1' && !cert && !allowPrivateHttp) {
    throw Error('plaintext public bind forbidden; use local TLS proxy or TLS_CERT/TLS_KEY');
  }
  const relay = new Relay({
    dataFile: config.dataFile ?? process.env.DATA_FILE ?? './data/devices.json',
    turnHost: config.turnHost ?? process.env.TURN_HOST ?? '',
    turnSecret: config.turnSecret ?? process.env.TURN_SECRET ?? '',
    enrollmentKey,
    maxDevices: config.maxDevices ?? 100,
  });
  const handler = (req, res) => {
    if (req.url === '/healthz' && req.method === 'GET') {
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end('{"status":"ok"}');
    } else {
      res.writeHead(404, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      res.end('{"error":"not_found"}');
    }
  };
  const server = cert && key
    ? createHttpsServer({ cert: readFileSync(cert), key: readFileSync(key) }, handler)
    : createHttpServer(handler);
  const peers = new Set();
  server.on('upgrade', (req, socket, head) => {
    if (peers.size >= 100) return socket.destroy();
    upgradeWebSocket(req, socket, head, (peer) => {
      peers.add(peer);
      peer.on('close', () => peers.delete(peer));
      relay.attach(peer, req.socket.remoteAddress ?? 'unknown');
    });
  });
  const timer = setInterval(() => {
    relay.cleanup();
    for (const peer of peers) {
      if (Date.now() - peer.lastPong > 120_000) peer.close(1001);
      else peer.ping();
    }
  }, 60_000);
  timer.unref();
  return {
    server, relay,
    async start() {
      await new Promise((yes, no) => { server.once('error', no); server.listen(port, bind, yes); });
      return server.address();
    },
    async close() {
      clearInterval(timer);
      for (const peer of peers) { peer.close(); peer.socket.destroy(); }
      if (server.listening) await new Promise((yes) => server.close(yes));
    },
  };
}

const runningDirectly = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (runningDirectly) {
  try {
    const app = createApp();
    const address = await app.start();
    console.log(JSON.stringify({ event: 'listening', address: address.address, port: address.port }));
    const shutdown = () => app.close().then(() => process.exit(0));
    process.on('SIGINT', shutdown);
    process.on('SIGTERM', shutdown);
  } catch (error) {
    console.error(JSON.stringify({ event: 'startup_failed', reason: error.message }));
    process.exitCode = 1;
  }
}
