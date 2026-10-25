import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import { gunzipSync } from "node:zlib";
import { createZip } from "./ffmpeg-zip.mjs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const VERSION = "b6.1.1";
if (Number(process.versions.node.split(".")[0]) < 18) {
  throw new Error(`需要 Node.js 18+ 执行 FFmpeg 资产下载，当前版本 ${process.versions.node}`);
}
const BASE = process.env.FFMPEG_TOOLS_BASE_URL
  || `https://github.com/eugeneware/ffmpeg-static/releases/download/${VERSION}`;
const PROXY = process.env.FFMPEG_TOOLS_PROXY?.trim() || "";
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const RESOURCES = path.join(ROOT, "phantom-services", "phantom-media", "src", "main", "resources", "ffmpeg");

const tools = {
  windows: {
    arch: "x86_64",
    license: "linux-x64.LICENSE",
    licenseSha: "8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903",
    ffmpeg: ["ffmpeg-win32-x64", "04e1307997530f9cf2fe35cba2ca7e8875ca91da02f89d6c7243df819c94ad00",
      "8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77", "ffmpeg.exe"],
    ffprobe: ["ffprobe-win32-x64", "3a7e2dc003dc2cd1472827e4c7c4f056ae1ae0ae7c5bbc580c99b49827351ba4",
      "f309e6223ad89d2fe54bccd420a7709b66fd27540674e92309578ed491a43c8d", "ffprobe.exe"],
  },
  macos: {
    arch: "aarch64",
    license: "darwin-arm64.LICENSE",
    licenseSha: "cb48bf09a11f5fb576cddb0431c8f5ed0a60157a9ec942adffc13907cbe083f2",
    ffmpeg: ["ffmpeg-darwin-arm64", "a90e3db6a3fd35f6074b013f948b1aa45b31c6375489d39e572bea3f18336584",
      "8923876afa8db5585022d7860ec7e589af192f441c56793971276d450ed3bbfa", "ffmpeg"],
    ffprobe: ["ffprobe-darwin-arm64", "bb2db6f5d8cef919da12fbf592119a987202a8c060a886f3cab091f9cab90b64",
      "d986a8ec7b030899fe66a8a288ed809a3543338705a3ce178cfb85869c5d80be", "ffprobe"],
  },
  linux: [
    {
      arch: "x86_64",
      license: "linux-x64.LICENSE",
      licenseSha: "8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903",
      ffmpeg: ["ffmpeg-linux-x64", "e7e7fb30477f717e6f55f9180a70386c62677ef8a4d4d1a5d948f4098aa3eb99",
        "bfe8a8fc511530457b528c48d77b5737527b504a3797a9bc4866aeca69c2dffa", "ffmpeg"],
      ffprobe: ["ffprobe-linux-x64", "4f231a1960d83e403d08f7971e271707bec278a9ae18e21b8b5b03186668450d",
        "25d9b6ccb05e3d9de9e04e31e2506d8dd7f9f0418981965ac6df12e8d3afd067", "ffprobe"],
    },
    {
      arch: "aarch64",
      license: "linux-x64.LICENSE",
      licenseSha: "8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903",
      ffmpeg: ["ffmpeg-linux-arm64", "6bb182d0d75d23028db82e9e4f723ca69b853d055698486e6984ddb2c06fb8ce",
        "754a678672298bc68156adff58aa7385a592c2b30b1d0ae8750c45c915c4bac0", "ffmpeg"],
      ffprobe: ["ffprobe-linux-arm64", "d17ae9b4c297d48e2521ba14e417bb0537c6ff77c584cdbcd6bb0d8d0307a2e8",
        "2ab6aba60ee84412dff9188720703376cb4e7aaf7e0b5e43aa8249f2acae5bf8", "ffprobe"],
    },
  ],
};

function sha256(data) {
  return createHash("sha256").update(data).digest("hex");
}

function runCurl(args, label) {
  return new Promise((resolve, reject) => {
    const child = spawn("curl", args, { stdio: ["ignore", "ignore", "pipe"] });
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr = (stderr + chunk.toString()).slice(-8_000);
    });
    child.on("error", (error) => reject(new Error(`${label} 启动 curl 失败: ${error.message}`)));
    child.on("close", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`${label} curl exit=${code}: ${stderr.trim() || "unknown error"}`));
    });
  });
}

async function downloadFromUrl(url, destination, label) {
  const partial = `${destination}.part`;
  let lastError;
  for (let attempt = 1; attempt <= 4; attempt++) {
    try {
      const args = ["--location", "--fail", "--silent", "--show-error", "--retry", "3",
        "--retry-delay", "2", "--retry-all-errors", "--connect-timeout", "30",
        "--max-time", "3600", "--continue-at", "-", "--output", partial];
      if (PROXY) args.push("--proxy", PROXY);
      args.push(url);
      await runCurl(args, label);
      if (!existsSync(partial) || statSync(partial).size === 0) throw new Error("empty download");
      renameSync(partial, destination);
      return readFileSync(destination);
    } catch (error) {
      lastError = error;
      console.warn(`[ffmpeg-tools] ${label} 下载失败 attempt=${attempt}: ${error.message}`);
      if (attempt < 4) await new Promise((resolve) => setTimeout(resolve, attempt * 2_000));
    }
  }
  throw new Error(`${label} 下载失败: ${lastError?.message ?? "unknown"}`);
}

async function downloadRaw(staging, asset, rawSha, compressedSha, outputName) {
  const output = path.join(staging, outputName);
  if (existsSync(output) && sha256(readFileSync(output)) === rawSha) {
    console.log(`[ffmpeg-tools] partial cache hit ${asset}`);
    return output;
  }
  const compressedPath = path.join(staging, `${asset}.gz`);
  let compressed;
  if (existsSync(compressedPath)) {
    compressed = readFileSync(compressedPath);
  } else {
    console.log(`[ffmpeg-tools] downloading ${asset}`);
    compressed = await downloadFromUrl(`${BASE}/${asset}.gz`, compressedPath, asset);
  }
  const actualCompressed = sha256(compressed);
  if (actualCompressed !== compressedSha) {
    rmSync(compressedPath, { force: true });
    throw new Error(`${asset}.gz SHA-256 不匹配: expected=${compressedSha} actual=${actualCompressed}`);
  }
  const raw = gunzipSync(compressed);
  const actualRaw = sha256(raw);
  if (actualRaw !== rawSha) {
    throw new Error(`${asset} 解压后 SHA-256 不匹配: expected=${rawSha} actual=${actualRaw}`);
  }
  writeFileSync(output, raw);
  return output;
}

async function downloadAndVerify(staging, asset, expectedSha) {
  const output = path.join(staging, asset);
  if (existsSync(output) && sha256(readFileSync(output)) === expectedSha) {
    return output;
  }
  console.log(`[ffmpeg-tools] downloading ${asset}`);
  const data = await downloadFromUrl(`${BASE}/${asset}`, output, asset);
  const actual = sha256(data);
  if (actual !== expectedSha) {
    rmSync(output, { force: true });
    throw new Error(`${asset} SHA-256 不匹配: expected=${expectedSha} actual=${actual}`);
  }
  return output;
}

const source = Buffer.from(`FFmpeg tools source:\nhttps://github.com/eugeneware/ffmpeg-static\nRelease: ${VERSION}\n\nFFmpeg source:\nhttps://github.com/FFmpeg/FFmpeg/tree/n6.1.1\n\nThe bundled binaries were obtained from the pinned eugeneware/ffmpeg-static release.\nRefer to LICENSE in this archive and verify the applicable LGPL/GPL obligations before redistribution.\n`, "utf8");

async function buildPlatform(platform, arch, definition) {
  const targetDir = path.join(RESOURCES, platform, arch);
  const target = path.join(targetDir, "ffmpeg-tools.zip");
  if (existsSync(target) && statSync(target).size > 0) {
    console.log(`[ffmpeg-tools] exists, skip download: ${target}`);
    return;
  }

  mkdirSync(targetDir, { recursive: true });
  const staging = path.join(targetDir, ".download");
  mkdirSync(staging, { recursive: true });
  let complete = false;
  try {
    // 串行下载，避免慢网络下多个大文件互相争用带宽。
    const ffmpegPath = await downloadRaw(staging, definition.ffmpeg[0], definition.ffmpeg[1],
      definition.ffmpeg[2], definition.ffmpeg[3]);
    const ffprobePath = await downloadRaw(staging, definition.ffprobe[0], definition.ffprobe[1],
      definition.ffprobe[2], definition.ffprobe[3]);
    const licensePath = await downloadAndVerify(staging, definition.license, definition.licenseSha);
    const zip = createZip([
      { name: definition.ffmpeg[3], data: readFileSync(ffmpegPath), mode: 0o100755 },
      { name: definition.ffprobe[3], data: readFileSync(ffprobePath), mode: 0o100755 },
      { name: "LICENSE", data: readFileSync(licensePath), mode: 0o100644 },
      { name: "SOURCE.txt", data: source, mode: 0o100644 },
    ]);
    const temp = `${target}.part`;
    writeFileSync(temp, zip);
    renameSync(temp, target);
    complete = true;
    console.log(`[ffmpeg-tools] wrote ${target} (${zip.length} bytes)`);
  } finally {
    if (complete) rmSync(staging, { recursive: true, force: true });
  }
}

const results = await Promise.allSettled([
  buildPlatform("windows", tools.windows.arch, tools.windows),
  buildPlatform("macos", tools.macos.arch, tools.macos),
  ...tools.linux.map((definition) => buildPlatform("linux", definition.arch, definition)),
]);
const failures = results.filter((result) => result.status === "rejected");
if (failures.length > 0) {
  for (const failure of failures) console.error(`[ffmpeg-tools] ${failure.reason}`);
  throw new Error(`${failures.length} 个平台的 FFmpeg 工具包构建失败`);
}
console.log("[ffmpeg-tools] all platform bundles are ready");
