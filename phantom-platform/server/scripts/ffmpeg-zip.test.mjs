import assert from "node:assert/strict";
import test from "node:test";
import { inflateRawSync } from "node:zlib";
import { createZip } from "./ffmpeg-zip.mjs";

test("creates deterministic executable zip entries", () => {
  const files = [
    { name: "ffmpeg", data: Buffer.from("ffmpeg-binary"), mode: 0o100755 },
    { name: "LICENSE", data: Buffer.from("license"), mode: 0o100644 },
  ];
  const first = createZip(files);
  const second = createZip(files);
  assert.deepEqual(first, second);
  assert.equal(first.readUInt32LE(0), 0x04034b50);
  const nameLength = first.readUInt16LE(26);
  const extraLength = first.readUInt16LE(28);
  const compressedLength = first.readUInt32LE(18);
  const name = first.subarray(30, 30 + nameLength).toString();
  const dataStart = 30 + nameLength + extraLength;
  const data = inflateRawSync(first.subarray(dataStart, dataStart + compressedLength));
  assert.equal(name, "ffmpeg");
  assert.equal(data.toString(), "ffmpeg-binary");
  assert.ok(first.includes(Buffer.from([0x50, 0x4b, 0x01, 0x02])));
  assert.ok(first.includes(Buffer.from([0x50, 0x4b, 0x05, 0x06])));
});
