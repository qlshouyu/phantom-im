import { deflateRawSync } from "node:zlib";

const crcTable = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    table[n] = c >>> 0;
  }
  return table;
})();
function crc32(data) {
  let crc = 0xffffffff;
  for (const byte of data) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function createZip(files) {
  const local = [];
  const central = [];
  let offset = 0;
  for (const file of files) {
    const name = Buffer.from(file.name, "utf8");
    const data = file.data;
    const compressed = deflateRawSync(data, { level: 9 });
    const crc = crc32(data);
    const localHeader = Buffer.alloc(30);
    localHeader.writeUInt32LE(0x04034b50, 0); localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0, 6); localHeader.writeUInt16LE(8, 8);
    localHeader.writeUInt16LE(0, 10); localHeader.writeUInt16LE(0x21, 12);
    localHeader.writeUInt32LE(crc, 14); localHeader.writeUInt32LE(compressed.length, 18);
    localHeader.writeUInt32LE(data.length, 22); localHeader.writeUInt16LE(name.length, 26);
    localHeader.writeUInt16LE(0, 28);
    local.push(localHeader, name, compressed);
    const centralHeader = Buffer.alloc(46);
    centralHeader.writeUInt32LE(0x02014b50, 0); centralHeader.writeUInt16LE(0x0314, 4);
    centralHeader.writeUInt16LE(20, 6); centralHeader.writeUInt16LE(0, 8);
    centralHeader.writeUInt16LE(8, 10); centralHeader.writeUInt16LE(0, 12);
    centralHeader.writeUInt16LE(0x21, 14); centralHeader.writeUInt32LE(crc, 16);
    centralHeader.writeUInt32LE(compressed.length, 20); centralHeader.writeUInt32LE(data.length, 24);
    centralHeader.writeUInt16LE(name.length, 28); centralHeader.writeUInt16LE(0, 30);
    centralHeader.writeUInt16LE(0, 32); centralHeader.writeUInt16LE(0, 34);
    centralHeader.writeUInt16LE(0, 36); centralHeader.writeUInt32LE((file.mode << 16) >>> 0, 38);
    centralHeader.writeUInt32LE(offset, 42);
    central.push(centralHeader, name);
    offset += 30 + name.length + compressed.length;
  }
  const centralData = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0); end.writeUInt16LE(0, 4); end.writeUInt16LE(0, 6);
  end.writeUInt16LE(files.length, 8); end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(centralData.length, 12); end.writeUInt32LE(offset, 16); end.writeUInt16LE(0, 20);
  return Buffer.concat([...local, centralData, end]);
}


export { crc32, createZip };
