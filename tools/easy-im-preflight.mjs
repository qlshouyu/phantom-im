#!/usr/bin/env node
/**
 * Easy IM 本地联调前置检查。
 *
 * 只使用 Node.js 内置模块，可在启动 Phantom、BFF、Web 和 Playwright 前执行：
 *   node tools/easy-im-preflight.mjs
 *   node tools/easy-im-preflight.mjs --json
 *
 * 任一依赖不可用时以非零状态退出，并逐项输出可定位的修复提示。
 */
import net from "node:net";
import http from "node:http";
import https from "node:https";

const env = process.env;
const checks = [
  {
    name: "MySQL",
    run: () => checkMysql({
      host: env.WEB_IM_DB_HOST ?? "127.0.0.1",
      port: Number(env.WEB_IM_DB_PORT ?? 3306),
    }),
    hint: "启动 deploy/local/docker-compose.yml 中的 mysql，并确认 3306 可访问",
  },
  {
    name: "Redis",
    run: () => checkRedis({
      host: env.WEB_IM_REDIS_HOST ?? "127.0.0.1",
      port: Number(env.WEB_IM_REDIS_PORT ?? 6379),
      password: env.WEB_IM_REDIS_PASSWORD ?? "123456",
    }),
    hint: "启动 deploy/local/docker-compose.yml 中的 redis，并确认 REDIS 密码",
  },
  {
    name: "RocketMQ Namesrv",
    run: () => checkTcp("RocketMQ Namesrv", env.ROCKETMQ_NAMESRV_HOST ?? "127.0.0.1", 9876),
    hint: "启动 deploy/local/rocketmq/docker-compose.yml，并确认 9876 可访问",
  },
  {
    name: "RocketMQ Broker",
    run: () => checkTcp("RocketMQ Broker", env.ROCKETMQ_BROKER_HOST ?? "127.0.0.1", 10911),
    hint: "确认 rmqbroker 正常运行且 10911 可访问",
  },
  {
    name: "Phantom 单体",
    run: () => checkHttp("Phantom 单体", env.PHANTOM_HTTP_URL ?? "http://127.0.0.1:28080", "/actuator/health"),
    hint: "启动 phantom-platform/server/phantom-launchers/phantom-boot-monolith，并确认 28080/actuator/health 为 UP",
  },
  {
    name: "Easy IM BFF",
    run: () => checkHttp("Easy IM BFF", env.EASY_IM_BFF_URL ?? "http://127.0.0.1:28090", "/actuator/health"),
    hint: "在 phantom-im/bff 加载 .env 后启动 Spring Boot，并确认 28090/actuator/health 为 UP",
  },
];

const json = process.argv.includes("--json");
const results = [];

for (const check of checks) {
  try {
    const detail = await check.run();
    results.push({ name: check.name, ok: true, detail });
    if (!json) console.log(`✓ ${check.name}: ${detail}`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    results.push({ name: check.name, ok: false, detail: message, hint: check.hint });
    if (!json) {
      console.error(`✗ ${check.name}: ${message}`);
      console.error(`  修复：${check.hint}`);
    }
  }
}

const failed = results.filter((item) => !item.ok);
if (json) {
  console.log(JSON.stringify({ ok: failed.length === 0, results }, null, 2));
}
if (failed.length > 0) {
  if (!json) console.error(`\n前置检查失败：${failed.length}/${results.length} 个依赖不可用。`);
  process.exitCode = 1;
} else if (!json) {
  console.log(`\n前置检查通过：${results.length}/${results.length} 个依赖可用。`);
}

function checkTcp(name, host, port) {
  return new Promise((resolve, reject) => {
    if (!Number.isInteger(port) || port <= 0 || port > 65535) {
      reject(new Error(`端口配置无效：${port}`));
      return;
    }
    const socket = net.createConnection({ host, port });
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error(`${host}:${port} 连接超时`));
    }, 3_000);
    socket.once("connect", () => {
      clearTimeout(timer);
      socket.end();
      resolve(`${name} ${host}:${port} 可连接`);
    });
    socket.once("error", (error) => {
      clearTimeout(timer);
      reject(new Error(`${host}:${port} ${error.message}`));
    });
  });
}

function checkMysql({ host, port }) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    let settled = false;
    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      error ? reject(error) : resolve(value);
    };
    const timer = setTimeout(() => finish(new Error(`${host}:${port} MySQL 握手超时`)), 3_000);
    socket.once("connect", () => {
      socket.once("data", (chunk) => {
        if (chunk.length < 5 || chunk[4] !== 10) {
          finish(new Error(`${host}:${port} 未返回 MySQL 协议握手`));
          return;
        }
        const packetLength = chunk.readUIntLE(0, 3);
        const versionEnd = chunk.indexOf(0, 5);
        const version = versionEnd > 5 ? chunk.subarray(5, versionEnd).toString("utf8") : "unknown";
        if (packetLength <= 0) {
          finish(new Error(`${host}:${port} MySQL 握手包无效`));
          return;
        }
        finish(null, `MySQL ${host}:${port} 握手正常（${version}）`);
      });
    });
    socket.once("error", (error) => finish(new Error(`${host}:${port} ${error.message}`)));
  });
}

function checkRedis({ host, port, password }) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host, port });
    let data = "";
    let settled = false;
    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      socket.destroy();
      error ? reject(error) : resolve(value);
    };
    const timer = setTimeout(() => finish(new Error(`${host}:${port} Redis 响应超时`)), 3_000);
    socket.once("connect", () => {
      const commands = password ? `AUTH ${password}\r\nPING\r\n` : "PING\r\n";
      socket.write(commands);
    });
    socket.on("data", (chunk) => {
      data += chunk.toString("utf8");
      if (data.includes("-ERR") || data.includes("-WRONGPASS") || data.includes("-NOAUTH")) {
        finish(new Error(`${host}:${port} Redis 认证失败：${data.trim()}`));
        return;
      }
      if (data.includes("+PONG")) finish(null, `Redis ${host}:${port} PING 正常`);
    });
    socket.once("error", (error) => finish(new Error(`${host}:${port} ${error.message}`)));
  });
}

function checkHttp(name, baseUrl, path) {
  return new Promise((resolve, reject) => {
    let url;
    try {
      url = new URL(path, baseUrl);
    } catch {
      reject(new Error(`地址无效：${baseUrl}`));
      return;
    }
    const client = url.protocol === "https:" ? https : http;
    const request = client.get(url, { timeout: 5_000 }, (response) => {
      let body = "";
      response.setEncoding("utf8");
      response.on("data", (chunk) => { body += chunk; });
      response.on("end", () => {
        let payload;
        try {
          payload = JSON.parse(body);
        } catch {
          reject(new Error(`${url} HTTP ${response.statusCode}，响应不是 JSON`));
          return;
        }
        const status = String(payload.status ?? "").toUpperCase();
        if (response.statusCode !== 200 || status !== "UP") {
          reject(new Error(`${url} HTTP ${response.statusCode}，status=${status || "unknown"}`));
          return;
        }
        resolve(`${name} ${url} 健康状态 UP`);
      });
    });
    request.on("timeout", () => request.destroy(new Error(`${url} 请求超时`)));
    request.on("error", (error) => reject(new Error(`${url} ${error.message}`)));
  });
}
