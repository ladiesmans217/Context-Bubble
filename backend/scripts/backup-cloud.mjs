import { spawn } from "node:child_process";
import { createCipheriv, randomBytes } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { pipeline } from "node:stream/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const required = ["SUPABASE_DB_URL", "SUPABASE_URL", "SUPABASE_SECRET_KEY", "BACKUP_ENCRYPTION_KEY"];
for (const name of required) if (!process.env[name]) throw new Error(`${name} is required`);
const key = Buffer.from(process.env.BACKUP_ENCRYPTION_KEY, "base64");
if (key.length !== 32) throw new Error("BACKUP_ENCRYPTION_KEY must decode to exactly 32 bytes");

const stamp = new Date().toISOString().replaceAll(":", "-");
const destination = resolve(process.env.BACKUP_DIRECTORY || "./backups");
const work = await mkdtemp(join(tmpdir(), "context-bubble-backup-"));
await mkdir(destination, { recursive: true });

try {
  await run("pg_dump", ["--format=custom", "--no-owner", "--no-acl", "--file", join(work, "database.dump"), process.env.SUPABASE_DB_URL]);
  const inventoryResponse = await fetch(`${process.env.SUPABASE_URL}/rest/v1/cloud_blobs?select=id,user_id,storage_path,kind,mime_type,encrypted_size_bytes,created_at,expires_at,pinned,deleted_at`, {
    headers: { apikey: process.env.SUPABASE_SECRET_KEY, Authorization: `Bearer ${process.env.SUPABASE_SECRET_KEY}` },
  });
  if (!inventoryResponse.ok) throw new Error(`Storage inventory failed (${inventoryResponse.status})`);
  await writeFile(join(work, "storage-inventory.json"), await inventoryResponse.text(), "utf8");
  await writeFile(join(work, "metadata.json"), JSON.stringify({ format: "context-bubble-backup-v1", createdAt: new Date().toISOString() }, null, 2));

  const archive = join(work, "bundle.tar");
  await run("tar", ["-cf", archive, "-C", work, "database.dump", "storage-inventory.json", "metadata.json"]);
  const nonce = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, nonce);
  const outputPath = join(destination, `context-bubble-${stamp}.tar.aes256gcm`);
  const output = createWriteStream(outputPath, { flags: "wx" });
  output.write(Buffer.from("CBACKUP1"));
  output.write(nonce);
  await pipeline(createReadStream(archive), cipher, output, { end: false });
  output.end(cipher.getAuthTag());
  await new Promise((resolveWrite, rejectWrite) => output.on("close", resolveWrite).on("error", rejectWrite));
  console.log(outputPath);
} finally {
  await rm(work, { recursive: true, force: true });
}

function run(command, args) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn(command, args, { stdio: "inherit", windowsHide: true });
    child.on("error", rejectRun);
    child.on("exit", (code) => code === 0 ? resolveRun() : rejectRun(new Error(`${command} exited with ${code}`)));
  });
}
