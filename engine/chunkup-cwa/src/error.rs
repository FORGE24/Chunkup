//! CWA 错误类型。

use std::fmt;

/// CWA 协议错误。
#[derive(Debug)]
pub enum CwaError {
    /// magic 不匹配。
    InvalidMagic { expected: [u8; 8], got: [u8; 8] },
    /// 版本不兼容。
    UnsupportedVersion { major: u16, minor: u16 },
    /// 偏移或大小越界。
    OutOfRange {
        what: &'static str,
        value: u64,
        max: u64,
    },
    /// 校验和不匹配。
    ChecksumMismatch {
        offset: u64,
        expected: u32,
        got: u32,
    },
    /// 结构体大小不符合预期(编译期不变量失败)。
    SizeInvariant {
        what: &'static str,
        expected: usize,
        got: usize,
    },
    /// IO 错误。
    Io(std::io::Error),
    /// 截断读取。
    UnexpectedEof { need: usize, have: usize },
}

impl fmt::Display for CwaError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidMagic { expected, got } => write!(
                f,
                "invalid magic: expected {:?}, got {:?}",
                expected, got
            ),
            Self::UnsupportedVersion { major, minor } => {
                write!(f, "unsupported version: major={}, minor={}", major, minor)
            }
            Self::OutOfRange { what, value, max } => {
                write!(f, "out of range: {} = {}, max = {}", what, value, max)
            }
            Self::ChecksumMismatch {
                offset,
                expected,
                got,
            } => write!(
                f,
                "checksum mismatch at offset {}: expected {:#010x}, got {:#010x}",
                offset, expected, got
            ),
            Self::SizeInvariant {
                what,
                expected,
                got,
            } => write!(
                f,
                "size invariant violated: {} expected {}, got {}",
                what, expected, got
            ),
            Self::Io(e) => write!(f, "io error: {}", e),
            Self::UnexpectedEof { need, have } => {
                write!(f, "unexpected end of data: need {} bytes, have {}", need, have)
            }
        }
    }
}

impl std::error::Error for CwaError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<std::io::Error> for CwaError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e)
    }
}

/// CWA Result 别名。
pub type CwaResult<T> = Result<T, CwaError>;
