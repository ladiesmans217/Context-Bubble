import { createClient } from "@supabase/supabase-js";
import type { Config } from "./config.ts";
import { CloudMemoryCrypto } from "./cloud-crypto.ts";
import type { AuthenticatedUser } from "./supabase-auth.ts";

export class CloudBlobService {
  private readonly crypto: CloudMemoryCrypto;

  constructor(private readonly config: Config) {
    this.crypto = new CloudMemoryCrypto(config.cloudMemoryMasterKeys, config.currentCloudMemoryKeyVersion);
  }

  get configured(): boolean {
    return Boolean(this.config.supabaseUrl && this.config.supabasePublishableKey && this.crypto.configured);
  }

  async saveGeneratedImage(user: AuthenticatedUser, bytes: Uint8Array, mimeType: string): Promise<{ id: string; sizeBytes: number }> {
    if (!this.config.supabaseUrl || !this.config.supabasePublishableKey || !this.config.supabaseSecretKey || !this.config.cloudMemoryMasterKeys.size) {
      throw new CloudBlobError("not_configured", "Encrypted cloud output storage is not configured");
    }
    if (bytes.length < 1 || bytes.length > 10 * 1024 * 1024) throw new CloudBlobError("invalid_blob", "Generated image must be between 1 byte and 10 MB");
    if (!ALLOWED_IMAGE_TYPES.has(mimeType)) throw new CloudBlobError("invalid_blob", "Generated image type is not supported");

    const client = createClient(this.config.supabaseUrl, this.config.supabasePublishableKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
      global: { headers: { Authorization: `Bearer ${user.accessToken}` } },
    });
    const { data: usage, error: usageError } = await client
      .from("cloud_blobs")
      .select("encrypted_size_bytes")
      .eq("user_id", user.id)
      .is("deleted_at", null);
    if (usageError) throw new CloudBlobError("cloud_read_failed", usageError.message);
    const usedBytes = (usage ?? []).reduce((sum, row) => sum + Number(row.encrypted_size_bytes ?? 0), 0);
    if (usedBytes + bytes.length > this.config.usageLimits.cloudBlobBytesPerUser) {
      throw new CloudBlobError("quota_exceeded", "Cloud output storage quota is full");
    }

    const encrypted = await this.crypto.encryptBytes(user.id, bytes, "generated-image");
    const packed = packEncryptedBlob(encrypted.keyVersion, encrypted.nonce, encrypted.ciphertext);
    const id = crypto.randomUUID();
    const path = `${user.id}/${id}.cbx`;
    const packedBuffer = packed.buffer.slice(packed.byteOffset, packed.byteOffset + packed.byteLength) as ArrayBuffer;
    const { error: storageError } = await client.storage.from("cloud-outputs").upload(path, new Blob([packedBuffer], { type: "application/octet-stream" }), {
      contentType: "application/octet-stream",
      upsert: false,
    });
    if (storageError) throw new CloudBlobError("cloud_write_failed", storageError.message);
    const { error: metadataError } = await client.from("cloud_blobs").insert({
      id,
      user_id: user.id,
      storage_path: path,
      kind: "GENERATED_IMAGE",
      mime_type: mimeType,
      encrypted_size_bytes: packed.length,
      key_version: encrypted.keyVersion,
      pinned: true,
    });
    if (metadataError) {
      await client.storage.from("cloud-outputs").remove([path]);
      throw new CloudBlobError("cloud_write_failed", metadataError.message);
    }
    return { id, sizeBytes: packed.length };
  }

  async usage(user: AuthenticatedUser): Promise<{ usedBytes: number; itemCount: number; limitBytes: number }> {
    const client = this.userClient(user);
    const { data, error } = await client.from("cloud_blobs")
      .select("encrypted_size_bytes")
      .eq("user_id", user.id)
      .is("deleted_at", null);
    if (error) throw new CloudBlobError("cloud_read_failed", error.message);
    return {
      usedBytes: (data ?? []).reduce((sum, row) => sum + Number(row.encrypted_size_bytes ?? 0), 0),
      itemCount: data?.length ?? 0,
      limitBytes: this.config.usageLimits.cloudBlobBytesPerUser,
    };
  }

  async deleteAll(user: AuthenticatedUser): Promise<void> {
    if (!this.config.supabaseUrl || !this.config.supabaseSecretKey) throw new CloudBlobError("not_configured", "Cloud output storage is not configured");
    const admin = createClient(this.config.supabaseUrl, this.config.supabaseSecretKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    });
    const { data, error } = await admin.from("cloud_blobs").select("storage_path").eq("user_id", user.id);
    if (error) throw new CloudBlobError("cloud_read_failed", error.message);
    const paths = (data ?? []).map((row) => String(row.storage_path));
    for (let offset = 0; offset < paths.length; offset += 100) {
      const { error: removeError } = await admin.storage.from("cloud-outputs").remove(paths.slice(offset, offset + 100));
      if (removeError) throw new CloudBlobError("cloud_delete_failed", removeError.message);
    }
  }

  private userClient(user: AuthenticatedUser) {
    if (!this.config.supabaseUrl || !this.config.supabasePublishableKey) throw new CloudBlobError("not_configured", "Cloud output storage is not configured");
    return createClient(this.config.supabaseUrl, this.config.supabasePublishableKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
      global: { headers: { Authorization: `Bearer ${user.accessToken}` } },
    });
  }
}

export class CloudBlobError extends Error {
  constructor(readonly code: string, message: string) { super(message); }
}

function packEncryptedBlob(keyVersion: number, nonce: Uint8Array, ciphertext: Uint8Array): Uint8Array {
  const output = new Uint8Array(4 + 4 + nonce.length + ciphertext.length);
  output.set([0x43, 0x42, 0x58, 0x31], 0);
  new DataView(output.buffer).setUint32(4, keyVersion, false);
  output.set(nonce, 8);
  output.set(ciphertext, 8 + nonce.length);
  return output;
}

const ALLOWED_IMAGE_TYPES = new Set(["image/png", "image/jpeg", "image/webp"]);
