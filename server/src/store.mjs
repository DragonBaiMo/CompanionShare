// Copyright (c) 2026 CompanionShare. MIT License.
// Only hashes of long random device credentials are stored on disk.
import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';

export const randomToken = (bytes = 32) => randomBytes(bytes).toString('base64url');
export const hashToken = (value) => createHash('sha256').update(value).digest('hex');

export function tokenMatches(provided, expectedHash) {
  if (typeof provided !== 'string' || !/^[A-Za-z0-9_-]{40,64}$/.test(provided)) return false;
  if (typeof expectedHash !== 'string' || !/^[0-9a-f]{64}$/.test(expectedHash)) return false;
  return timingSafeEqual(Buffer.from(hashToken(provided), 'hex'), Buffer.from(expectedHash, 'hex'));
}

export class DeviceStore {
  constructor(filePath) {
    this.filePath = resolve(filePath);
    try {
      const parsed = JSON.parse(readFileSync(this.filePath, 'utf8'));
      if (parsed.version !== 1 || typeof parsed.devices !== 'object' || Array.isArray(parsed.devices)) {
        throw new Error('unsupported store schema');
      }
      this.devices = parsed.devices;
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      this.devices = {};
    }
  }

  get(id) {
    return Object.hasOwn(this.devices, id) ? this.devices[id] : null;
  }

  register(id, secret) {
    if (typeof id !== 'string' || !/^[A-Za-z0-9_-]{20,32}$/.test(id)) return false;
    if (typeof secret !== 'string' || !/^[A-Za-z0-9_-]{40,64}$/.test(secret)) return false;
    const known = this.get(id);
    if (known) return tokenMatches(secret, known.ownerHash);
    this.devices[id] = { ownerHash: hashToken(secret), viewerHash: null };
    this.save();
    return true;
  }

  setViewer(id, token) {
    const device = this.get(id);
    if (!device) throw new Error('unknown device');
    device.viewerHash = token ? hashToken(token) : null;
    this.save();
  }

  save() {
    mkdirSync(dirname(this.filePath), { recursive: true, mode: 0o700 });
    const temp = `${this.filePath}.${randomToken(8)}.tmp`;
    writeFileSync(temp, JSON.stringify({ version: 1, devices: this.devices }), { mode: 0o600 });
    renameSync(temp, this.filePath);
  }
}
