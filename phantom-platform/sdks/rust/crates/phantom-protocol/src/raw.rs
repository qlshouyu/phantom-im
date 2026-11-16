//! 最小 wire 扫描器：保留未知字段的原始编码，不重新编码未知载荷。
use phantom_domain::{ErrorKind, SdkError, SdkResult};

#[derive(Clone, Debug)]
pub struct RawField<'a> {
    pub number: u32,
    pub wire_type: u8,
    pub encoded: &'a [u8],
    pub value: &'a [u8],
}

fn invalid() -> SdkError {
    SdkError::new(ErrorKind::Protocol, "Protobuf wire 字段损坏")
}

fn varint(raw: &[u8], offset: &mut usize) -> SdkResult<u64> {
    let mut value = 0;
    for shift in (0..70).step_by(7) {
        let byte = *raw.get(*offset).ok_or_else(invalid)?;
        *offset += 1;
        if shift == 63 && byte > 1 {
            return Err(invalid());
        }
        value |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(invalid())
}

fn tag(raw: &[u8], offset: &mut usize) -> SdkResult<(u32, u8)> {
    let key = varint(raw, offset)?;
    let number = u32::try_from(key >> 3).map_err(|_| invalid())?;
    if number == 0 || number > 0x1fff_ffff {
        return Err(invalid());
    }
    Ok((number, (key & 7) as u8))
}

fn skip(
    raw: &[u8],
    offset: &mut usize,
    number: u32,
    kind: u8,
    depth: usize,
) -> SdkResult<(usize, usize)> {
    let start = *offset;
    let end = match kind {
        0 => {
            varint(raw, offset)?;
            *offset
        }
        1 => start.checked_add(8).ok_or_else(invalid)?,
        2 => {
            let len = usize::try_from(varint(raw, offset)?).map_err(|_| invalid())?;
            let value_start = *offset;
            let end = value_start.checked_add(len).ok_or_else(invalid)?;
            if end > raw.len() {
                return Err(invalid());
            }
            *offset = end;
            return Ok((value_start, end));
        }
        3 => {
            if depth >= 100 {
                return Err(invalid());
            }
            loop {
                let (child, wire) = tag(raw, offset)?;
                if wire == 4 {
                    if child != number {
                        return Err(invalid());
                    }
                    break;
                }
                skip(raw, offset, child, wire, depth + 1)?;
            }
            *offset
        }
        5 => start.checked_add(4).ok_or_else(invalid)?,
        _ => return Err(invalid()),
    };
    if end > raw.len() {
        return Err(invalid());
    }
    *offset = end;
    Ok((start, end))
}

pub fn fields(raw: &[u8]) -> SdkResult<Vec<RawField<'_>>> {
    let mut offset = 0;
    let mut output = Vec::new();
    while offset < raw.len() {
        let start = offset;
        let (number, wire_type) = tag(raw, &mut offset)?;
        let (value_start, value_end) = skip(raw, &mut offset, number, wire_type, 0)?;
        output.push(RawField {
            number,
            wire_type,
            encoded: &raw[start..offset],
            value: &raw[value_start..value_end],
        });
    }
    Ok(output)
}

/// 单个 message 字段可以重复编码，Protobuf 合并其子消息。
pub(crate) fn message_bytes(raw: &[u8], number: u32) -> SdkResult<Vec<u8>> {
    let mut value = Vec::new();
    for field in fields(raw)?.into_iter().filter(|f| f.number == number) {
        if field.wire_type != 2 {
            return Err(invalid());
        }
        value.extend_from_slice(field.value);
    }
    Ok(value)
}

pub(crate) fn hex(raw: &[u8]) -> String {
    raw.iter().map(|b| format!("{b:02x}")).collect()
}
