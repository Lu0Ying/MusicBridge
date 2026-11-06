import { readFileSync } from 'node:fs';

/**
 * 读取音频容器的时长，仅用于给服务端一个可选提示值。
 * 读不出来就返回 null（调用方不传 durationMs），不做任何码率估算或猜测。
 * 注意：这里只解析容器/头部信息，不等于完整解码，也不保证能识破刻意伪造时长字段的文件。
 */
export function probeDurationMs(filePath: string): number | null {
  let buffer: Buffer;
  try {
    buffer = readFileSync(filePath);
  } catch {
    return null;
  }
  try {
    if (buffer.length >= 12 && buffer.toString('latin1', 0, 4) === 'RIFF' && buffer.toString('latin1', 8, 12) === 'WAVE') {
      return wavDuration(buffer);
    }
    if (buffer.length >= 4 && buffer.toString('latin1', 0, 4) === 'fLaC') {
      return flacDuration(buffer);
    }
    if (buffer.length >= 4 && buffer.toString('latin1', 0, 4) === 'OggS') {
      return oggDuration(buffer);
    }
    if (buffer.length >= 8 && buffer.toString('latin1', 4, 8) === 'ftyp') {
      return m4aDuration(buffer);
    }
    return mp3Duration(buffer);
  } catch {
    return null;
  }
}

function toMilliseconds(value: number): number | null {
  if (!Number.isFinite(value) || value <= 0) {
    return null;
  }
  return Math.round(value);
}

function wavDuration(buffer: Buffer): number | null {
  let offset = 12;
  let byteRate = 0;
  while (offset + 8 <= buffer.length) {
    const chunkId = buffer.toString('latin1', offset, offset + 4);
    const chunkSize = buffer.readUInt32LE(offset + 4);
    if (chunkId === 'fmt ' && chunkSize >= 16) {
      byteRate = buffer.readUInt32LE(offset + 16);
    }
    if (chunkId === 'data') {
      if (byteRate <= 0) {
        return null;
      }
      const dataSize = Math.min(chunkSize, buffer.length - offset - 8);
      return toMilliseconds((dataSize / byteRate) * 1000);
    }
    if (chunkSize === 0) {
      break;
    }
    offset += 8 + chunkSize;
  }
  return null;
}

function flacDuration(buffer: Buffer): number | null {
  // 4 字节 "fLaC" 后紧跟元数据块头，第一块必须是 STREAMINFO（type=0，长度 34）
  if (buffer.length < 34) {
    return null;
  }
  const blockType = buffer[4] & 0x7f;
  const blockLength = buffer.readUIntBE(5, 3);
  if (blockType !== 0 || blockLength < 18) {
    return null;
  }
  // STREAMINFO 从文件偏移 8 开始：采样率 20 bit、声道 3 bit、位深 5 bit、总采样数 36 bit
  const streamInfo = buffer.subarray(8);
  const sampleRate = streamInfo.readUInt32BE(10) >>> 12;
  const totalSamples = (streamInfo.readUInt32BE(13) & 0x0f) * 4294967296 + streamInfo.readUInt32BE(14);
  if (sampleRate <= 0 || totalSamples <= 0) {
    return null;
  }
  return toMilliseconds((totalSamples / sampleRate) * 1000);
}

function mp3Duration(buffer: Buffer): number | null {
  let offset = 0;
  if (buffer.length >= 10 && buffer.toString('latin1', 0, 3) === 'ID3') {
    const version = buffer[3];
    if (version === 3 || version === 4) {
      const size =
        ((buffer[6] & 0x7f) << 21) |
        ((buffer[7] & 0x7f) << 14) |
        ((buffer[8] & 0x7f) << 7) |
        (buffer[9] & 0x7f);
      offset = 10 + size + (version === 4 && (buffer[5] & 0x10) ? 10 : 0);
    }
  }

  const sampleRateTable: Record<number, number[]> = {
    3: [44100, 48000, 32000],
    2: [22050, 24000, 16000],
    0: [11025, 12000, 8000]
  };

  const limit = Math.min(buffer.length, offset + 8192);
  for (let index = offset; index + 4 <= limit; index += 1) {
    if (buffer[index] !== 0xff || (buffer[index + 1] & 0xe0) !== 0xe0) {
      continue;
    }
    const versionBits = (buffer[index + 1] >> 3) & 0x03;
    const layerBits = (buffer[index + 1] >> 1) & 0x03;
    if (versionBits === 1 || layerBits === 0) {
      continue;
    }
    const sampleRateIndex = (buffer[index + 2] >> 2) & 0x03;
    const sampleRates = sampleRateTable[versionBits];
    if (!sampleRates || sampleRateIndex >= sampleRates.length) {
      continue;
    }
    const sampleRate = sampleRates[sampleRateIndex];
    const samplesPerFrame =
      layerBits === 3 ? 384 : layerBits === 2 ? 1152 : versionBits === 3 ? 1152 : 576;
    const channelMode = (buffer[index + 3] >> 6) & 0x03;
    const sideInfoSize =
      versionBits === 3 ? (channelMode === 3 ? 17 : 32) : channelMode === 3 ? 9 : 17;

    // Xing/Info 头（VBR 与部分 CBR 文件）给出总帧数，据此精确计算时长
    const xingOffset = index + 4 + sideInfoSize;
    if (xingOffset + 12 <= buffer.length) {
      const magic = buffer.toString('latin1', xingOffset, xingOffset + 4);
      if (magic === 'Xing' || magic === 'Info') {
        const flags = buffer.readUInt32BE(xingOffset + 4);
        if ((flags & 0x01) === 0x01) {
          const frames = buffer.readUInt32BE(xingOffset + 8);
          if (frames > 0) {
            return toMilliseconds((frames * samplesPerFrame) / sampleRate * 1000);
          }
        }
      }
    }

    // VBRI 头（Fraunhofer 编码器）固定位于帧头后 36 字节
    if (index + 36 + 18 <= buffer.length && buffer.toString('latin1', index + 36, index + 40) === 'VBRI') {
      const frames = buffer.readUInt32BE(index + 36 + 14);
      if (frames > 0) {
        return toMilliseconds((frames * samplesPerFrame) / sampleRate * 1000);
      }
    }
    // 找到合法帧头但没有总帧数信息：不做码率估算，直接放弃
    return null;
  }
  return null;
}

function oggDuration(buffer: Buffer): number | null {
  if (buffer.length < 27) {
    return null;
  }
  const firstPacketStart = 27 + buffer[26];
  let sampleRate = 0;
  if (buffer.length >= firstPacketStart + 16) {
    if (buffer[firstPacketStart] === 0x01 && buffer.toString('latin1', firstPacketStart + 1, firstPacketStart + 7) === 'vorbis') {
      sampleRate = buffer.readUInt32LE(firstPacketStart + 12);
    } else if (buffer.toString('latin1', firstPacketStart, firstPacketStart + 8) === 'OpusHead') {
      // Opus 的 granule 一律按 48 kHz 计
      sampleRate = 48000;
    }
  }
  if (sampleRate <= 0) {
    return null;
  }

  // 从尾部找最后一个已结束页面的 granule position（-1 表示该页无完整包）
  for (let index = buffer.length - 4; index >= 0; index -= 1) {
    if (
      buffer[index] !== 0x4f ||
      buffer[index + 1] !== 0x67 ||
      buffer[index + 2] !== 0x67 ||
      buffer[index + 3] !== 0x53
    ) {
      continue;
    }
    if (index + 14 > buffer.length) {
      continue;
    }
    const granule = Number(buffer.readBigInt64LE(index + 6));
    if (granule > 0) {
      return toMilliseconds((granule / sampleRate) * 1000);
    }
  }
  return null;
}

interface BoxRange {
  dataStart: number;
  end: number;
}

function findBox(buffer: Buffer, start: number, end: number, type: string): BoxRange | null {
  let offset = start;
  while (offset + 8 <= end) {
    const size = buffer.readUInt32BE(offset);
    const boxType = buffer.toString('latin1', offset + 4, offset + 8);
    let headerSize = 8;
    let boxSize = size;
    if (size === 1) {
      if (offset + 16 > end) {
        return null;
      }
      boxSize = Number(buffer.readBigUInt64BE(offset + 8));
      headerSize = 16;
    } else if (size === 0) {
      boxSize = end - offset;
    }
    if (boxSize < headerSize || offset + boxSize > end) {
      return null;
    }
    if (boxType === type) {
      return { dataStart: offset + headerSize, end: offset + boxSize };
    }
    offset += boxSize;
  }
  return null;
}

function m4aDuration(buffer: Buffer): number | null {
  const moov = findBox(buffer, 0, buffer.length, 'moov');
  if (!moov) {
    return null;
  }
  const mvhd = findBox(buffer, moov.dataStart, moov.end, 'mvhd');
  if (!mvhd || mvhd.end - mvhd.dataStart < 20) {
    return null;
  }
  const version = buffer[mvhd.dataStart];
  let timescale: number;
  let duration: number;
  if (version === 1) {
    if (mvhd.end - mvhd.dataStart < 32) {
      return null;
    }
    timescale = buffer.readUInt32BE(mvhd.dataStart + 20);
    duration = Number(buffer.readBigUInt64BE(mvhd.dataStart + 24));
  } else {
    timescale = buffer.readUInt32BE(mvhd.dataStart + 12);
    duration = buffer.readUInt32BE(mvhd.dataStart + 16);
  }
  if (timescale <= 0 || duration <= 0) {
    return null;
  }
  return toMilliseconds((duration / timescale) * 1000);
}
