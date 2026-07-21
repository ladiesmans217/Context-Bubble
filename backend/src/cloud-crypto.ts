import type { CloudMemoryPayload } from "./contracts.ts";

export type EncryptedMemoryPayload = {
  ciphertext: string;
  nonce: string;
  keyVersion: number;
  contentHmac: string;
};

export type EncryptedBinaryPayload = {
  ciphertext: Uint8Array;
  nonce: Uint8Array;
  keyVersion: number;
};

export class CloudMemoryCrypto {
  constructor(
    private readonly masterKeys: ReadonlyMap<number, string>,
    private readonly currentKeyVersion: number,
  ) {
    if (currentKeyVersion > 0 && !masterKeys.has(currentKeyVersion)) {
      throw new Error("Current cloud-memory key version is unavailable");
    }
  }

  get configured(): boolean {
    return this.currentKeyVersion > 0;
  }

  async encrypt(userId: string, payload: CloudMemoryPayload): Promise<EncryptedMemoryPayload> {
    const version = this.currentKeyVersion;
    const secret = this.masterKeys.get(version);
    if (!secret) throw new CloudCryptoError("Cloud-memory encryption is not configured");
    const encryptionKey = await deriveAesKey(secret, userId, version);
    const nonce = crypto.getRandomValues(new Uint8Array(new ArrayBuffer(12)));
    const plaintext = encoder.encode(JSON.stringify(payload));
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: nonce, additionalData: toArrayBuffer(associatedData(userId, version)), tagLength: 128 },
      encryptionKey,
      toArrayBuffer(plaintext),
    );
    const hmacKey = await deriveHmacKey(secret, userId, version);
    const canonical = encoder.encode(`${payload.type}\n${payload.summary.trim()}\n${payload.value.trim()}`);
    const hmac = await crypto.subtle.sign("HMAC", hmacKey, toArrayBuffer(canonical));
    return {
      ciphertext: encodeBase64Url(new Uint8Array(ciphertext)),
      nonce: encodeBase64Url(nonce),
      keyVersion: version,
      contentHmac: encodeBase64Url(new Uint8Array(hmac)),
    };
  }

  async encryptBytes(userId: string, bytes: Uint8Array, purpose: string): Promise<EncryptedBinaryPayload> {
    const version = this.currentKeyVersion;
    const secret = this.masterKeys.get(version);
    if (!secret) throw new CloudCryptoError("Cloud-memory encryption is not configured");
    const encryptionKey = await deriveAesKey(secret, userId, version);
    const nonce = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: nonce, additionalData: toArrayBuffer(encoder.encode(`context-bubble:${purpose}:${userId}:v${version}`)), tagLength: 128 },
      encryptionKey,
      toArrayBuffer(bytes),
    );
    return { ciphertext: new Uint8Array(ciphertext), nonce, keyVersion: version };
  }

  async decrypt(
    userId: string,
    encrypted: Pick<EncryptedMemoryPayload, "ciphertext" | "nonce" | "keyVersion">,
  ): Promise<CloudMemoryPayload> {
    const secret = this.masterKeys.get(encrypted.keyVersion);
    if (!secret) throw new CloudCryptoError(`Cloud-memory key V${encrypted.keyVersion} is unavailable`);
    const key = await deriveAesKey(secret, userId, encrypted.keyVersion);
    try {
      const plaintext = await crypto.subtle.decrypt(
        {
          name: "AES-GCM",
          iv: toArrayBuffer(decodeBase64Url(encrypted.nonce)),
          additionalData: toArrayBuffer(associatedData(userId, encrypted.keyVersion)),
          tagLength: 128,
        },
        key,
        toArrayBuffer(decodeBase64Url(encrypted.ciphertext)),
      );
      return JSON.parse(decoder.decode(plaintext)) as CloudMemoryPayload;
    } catch {
      throw new CloudCryptoError("Cloud memory failed authenticated decryption");
    }
  }

  async decryptBytes(userId: string, encrypted: EncryptedBinaryPayload, purpose: string): Promise<Uint8Array> {
    const secret = this.masterKeys.get(encrypted.keyVersion);
    if (!secret) throw new CloudCryptoError(`Cloud-memory key V${encrypted.keyVersion} is unavailable`);
    const key = await deriveAesKey(secret, userId, encrypted.keyVersion);
    try {
      const plaintext = await crypto.subtle.decrypt(
        {
          name: "AES-GCM",
          iv: toArrayBuffer(encrypted.nonce),
          additionalData: toArrayBuffer(encoder.encode(`context-bubble:${purpose}:${userId}:v${encrypted.keyVersion}`)),
          tagLength: 128,
        },
        key,
        toArrayBuffer(encrypted.ciphertext),
      );
      return new Uint8Array(plaintext);
    } catch {
      throw new CloudCryptoError("Cloud binary data failed authenticated decryption");
    }
  }
}

async function deriveAesKey(secret: string, userId: string, version: number): Promise<CryptoKey> {
  const material = await importHkdfMaterial(secret);
  return crypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: encoder.encode(`context-bubble:${userId}`),
      info: encoder.encode(`cloud-memory:aes-gcm:v${version}`),
    },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

async function deriveHmacKey(secret: string, userId: string, version: number): Promise<CryptoKey> {
  const material = await importHkdfMaterial(secret);
  return crypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt: encoder.encode(`context-bubble:${userId}`),
      info: encoder.encode(`cloud-memory:hmac:v${version}`),
    },
    material,
    { name: "HMAC", hash: "SHA-256", length: 256 },
    false,
    ["sign", "verify"],
  );
}

async function importHkdfMaterial(secret: string): Promise<CryptoKey> {
  return crypto.subtle.importKey("raw", encoder.encode(secret), "HKDF", false, ["deriveKey"]);
}

function associatedData(userId: string, version: number): Uint8Array {
  return encoder.encode(`context-bubble|${userId}|memory|v${version}`);
}

export function encodeBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

export function decodeBase64Url(value: string): Uint8Array {
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export class CloudCryptoError extends Error {}
