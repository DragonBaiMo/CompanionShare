// Copyright (c) 2026 CompanionShare. MIT License.
import { createHmac } from 'node:crypto';

// NOTE: coturn's time-limited REST credentials use HMAC-SHA1 by protocol design.
export function makeIceServers(config, id, now = Date.now()) {
  const list = [{ urls: [config.turnHost
    ? `stun:${config.turnHost}:3478` : 'stun:stun.l.google.com:19302'] }];
  if (!config.turnHost || !config.turnSecret) return list;
  const username = `${Math.floor(now / 1000) + 3600}:${id}`;
  const credential = createHmac('sha1', config.turnSecret).update(username).digest('base64');
  list.push({ urls: [`turn:${config.turnHost}:3478?transport=udp`, `turn:${config.turnHost}:3478?transport=tcp`], username, credential });
  return list;
}
