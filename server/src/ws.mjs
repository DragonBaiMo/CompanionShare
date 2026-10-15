// Copyright (c) 2026 CompanionShare. MIT License.
// Small RFC 6455 text-only endpoint: masked client frames, bounded packets, no extensions.
import { createHash } from 'node:crypto';
import { EventEmitter } from 'node:events';

const MAGIC = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const decoder = new TextDecoder('utf-8', { fatal: true });
export const MAX_FRAME = 262144;

export class WsPeer extends EventEmitter {
  constructor(socket) {
    super();
    this.socket = socket;
    this.buffer = Buffer.alloc(0);
    this.closed = false;
    this.lastPong = Date.now();
    socket.on('data', (chunk) => this.feed(chunk));
    socket.on('error', (error) => this.emit('error', error));
    socket.on('close', () => { this.closed = true; this.emit('close'); });
  }

  send(data) { return this.frame(0x1, Buffer.from(JSON.stringify(data))); }
  ping() { return this.frame(0x9, Buffer.alloc(0)); }

  frame(opcode, body) {
    if (this.closed || this.socket.destroyed) return false;
    if (body.length > MAX_FRAME) return false;
    const header = body.length < 126 ? Buffer.alloc(2)
      : body.length <= 65535 ? Buffer.alloc(4) : Buffer.alloc(10);
    header[0] = 0x80 | opcode;
    if (body.length < 126) header[1] = body.length;
    else if (body.length <= 65535) {
      header[1] = 126; header.writeUInt16BE(body.length, 2);
    } else {
      header[1] = 127; header.writeBigUInt64BE(BigInt(body.length), 2);
    }
    return this.socket.write(Buffer.concat([header, body]));
  }

  close(code = 1000) {
    if (this.closed) return;
    this.closed = true;
    const body = Buffer.alloc(2); body.writeUInt16BE(code);
    const header = Buffer.from([0x88, 0x02]);
    if (!this.socket.destroyed) this.socket.end(Buffer.concat([header, body]));
  }

  fail(code = 1002) { this.close(code); }

  feed(chunk) {
    if (this.closed) return;
    if (this.buffer.length + chunk.length > MAX_FRAME + 14) return this.fail(1009);
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 2 && !this.closed) {
      const first = this.buffer[0];
      const second = this.buffer[1];
      const opcode = first & 0x0f;
      if ((first & 0x70) || !(first & 0x80) || !(second & 0x80)) return this.fail();
      if (![0x1, 0x8, 0x9, 0xa].includes(opcode)) return this.fail(1003);
      let length = second & 0x7f;
      let offset = 2;
      if (length === 126) {
        if (this.buffer.length < 4) return;
        length = this.buffer.readUInt16BE(2); offset = 4;
      } else if (length === 127) {
        if (this.buffer.length < 10) return;
        const bigLength = this.buffer.readBigUInt64BE(2);
        if (bigLength > BigInt(MAX_FRAME)) return this.fail(1009);
        length = Number(bigLength); offset = 10;
      }
      if (length > MAX_FRAME || (opcode >= 8 && length > 125)) return this.fail(1009);
      const fullSize = offset + 4 + length;
      if (this.buffer.length < fullSize) return;
      const mask = this.buffer.subarray(offset, offset + 4);
      const payload = Buffer.from(this.buffer.subarray(offset + 4, fullSize));
      this.buffer = this.buffer.subarray(fullSize);
      for (let i = 0; i < length; i++) payload[i] ^= mask[i % 4];
      if (opcode === 0x8) return this.close();
      if (opcode === 0x9) { this.frame(0xa, payload); continue; }
      if (opcode === 0xa) { this.lastPong = Date.now(); continue; }
      try { this.emit('message', decoder.decode(payload)); }
      catch { return this.fail(1007); }
    }
  }
}

export function upgradeWebSocket(req, socket, head, onPeer) {
  const key = req.headers['sec-websocket-key'];
  const valid = req.url === '/ws' && req.method === 'GET'
    && req.headers.upgrade?.toLowerCase() === 'websocket'
    && req.headers['sec-websocket-version'] === '13'
    && typeof key === 'string' && /^[A-Za-z0-9+/]{22}==$/.test(key);
  if (!valid) { socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n'); return; }
  const accept = createHash('sha1').update(key + MAGIC).digest('base64');
  socket.write('HTTP/1.1 101 Switching Protocols\r\n'
    + 'Upgrade: websocket\r\nConnection: Upgrade\r\n'
    + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`);
  const peer = new WsPeer(socket);
  onPeer(peer, req);
  if (head.length) peer.feed(head);
}
