use crate::error::KernelError;
use crc32fast::Hasher as Crc32;
use md5::Md5;
use sha1::Sha1;
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;

/// 一次流式扫描得到的全部摘要。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Hashes {
    pub md5: String,
    pub sha1: String,
    pub sha256: String,
    pub crc32: u32,
    pub size: u64,
}

/// 读文件的同时算 MD5 / SHA1 / SHA256 / CRC32。
///
/// 只读一遍盘：这在秒传场景很关键——一个 2 GB 的视频，如果分开算三种
/// 摘要就要读三遍，在手机上会多花好几秒的 IO。
pub fn hash_file(path: &Path) -> Result<Hashes, KernelError> {
    let file = File::open(path)?;
    let size = file.metadata()?.len();
    let mut reader = BufReader::with_capacity(BUFFER_SIZE, file);
    let mut digests = Digests::new();
    let mut buf = vec![0u8; BUFFER_SIZE];

    loop {
        let read = reader.read(&mut buf)?;
        if read == 0 {
            break;
        }
        digests.update(&buf[..read]);
    }

    let mut hashes = digests.finish();
    hashes.size = size;
    Ok(hashes)
}

/// 只对内存里的一块数据算摘要。用于小文件、签名串。
pub fn hash_bytes(bytes: &[u8]) -> Hashes {
    let mut digests = Digests::new();
    digests.update(bytes);
    let mut hashes = digests.finish();
    hashes.size = bytes.len() as u64;
    hashes
}

/// 对文件的某个分片单独算 MD5。
///
/// 百度网盘的 precreate 接口要求上传前提供每个 4 MiB 分片的 MD5，
/// 这里直接 seek 过去只读这一段，不碰其余部分。
pub fn slice_md5(path: &Path, offset: u64, length: u64) -> Result<String, KernelError> {
    let mut file = File::open(path)?;
    let file_size = file.metadata()?.len();

    let end = offset
        .checked_add(length)
        .ok_or_else(|| KernelError::Argument("offset + length 溢出".into()))?;

    if end > file_size {
        return Err(KernelError::Range {
            offset,
            length,
            file_size,
        });
    }

    use std::io::{Seek, SeekFrom};
    file.seek(SeekFrom::Start(offset))?;

    let mut reader = BufReader::with_capacity(BUFFER_SIZE, file);
    let mut hasher = Md5::new();
    let mut remaining = length;
    let mut buf = vec![0u8; BUFFER_SIZE];

    while remaining > 0 {
        let want = std::cmp::min(remaining as usize, buf.len());
        let read = reader.read(&mut buf[..want])?;
        if read == 0 {
            return Err(KernelError::Range {
                offset,
                length,
                file_size,
            });
        }
        hasher.update(&buf[..read]);
        remaining -= read as u64;
    }

    Ok(hex::encode(hasher.finalize()))
}

/// 把四个哈希器的更新逻辑收在一起，保证每个缓冲块只被处理一次。
struct Digests {
    md5: Md5,
    sha1: Sha1,
    sha256: Sha256,
    crc32: Crc32,
}

impl Digests {
    fn new() -> Self {
        Self {
            md5: Md5::new(),
            sha1: Sha1::new(),
            sha256: Sha256::new(),
            crc32: Crc32::new(),
        }
    }

    fn update(&mut self, bytes: &[u8]) {
        self.md5.update(bytes);
        self.sha1.update(bytes);
        self.sha256.update(bytes);
        self.crc32.update(bytes);
    }

    fn finish(self) -> Hashes {
        Hashes {
            md5: hex::encode(self.md5.finalize()),
            sha1: hex::encode(self.sha1.finalize()),
            sha256: hex::encode(self.sha256.finalize()),
            crc32: self.crc32.finalize(),
            size: 0,
        }
    }
}

const BUFFER_SIZE: usize = 256 * 1024;

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn temp_file(bytes: &[u8]) -> tempfile::NamedTempFile {
        let mut f = tempfile::NamedTempFile::new().unwrap();
        f.write_all(bytes).unwrap();
        f.flush().unwrap();
        f
    }

    #[test]
    fn empty_file_hashes_are_well_known() {
        let f = temp_file(b"");
        let h = hash_file(f.path()).unwrap();
        assert_eq!(
            h.md5,
            "d41d8cd98f00b204e9800998ecf8427e",
            "空文件 MD5 是 RFC 1321 的固定值"
        );
        assert_eq!(
            h.sha256,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(h.crc32, 0);
        assert_eq!(h.size, 0);
    }

    #[test]
    fn abc_matches_published_vectors() {
        let f = temp_file(b"abc");
        let h = hash_file(f.path()).unwrap();
        assert_eq!(h.md5, "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(h.sha1, "a9993e364706816aba3e25717850c26c9cd0d89d");
        assert_eq!(
            h.sha256,
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(h.crc32, 0x3524_41C2);
        assert_eq!(h.size, 3);
    }

    #[test]
    fn slice_md5_only_covers_requested_range() {
        let f = temp_file(b"abcdefghij");
        assert_eq!(
            slice_md5(f.path(), 0, 3).unwrap(),
            "900150983cd24fb0d6963f7d28e17f72",
            "前 3 字节就是 abc 的 MD5"
        );
        assert_eq!(
            slice_md5(f.path(), 3, 4).unwrap(),
            hex::encode(Md5::digest(b"defg"))
        );
        assert_eq!(slice_md5(f.path(), 10, 0).unwrap(), "d41d8cd98f00b204e9800998ecf8427e");
    }

    #[test]
    fn slice_out_of_range_is_rejected() {
        let f = temp_file(b"abc");
        assert!(matches!(
            slice_md5(f.path(), 2, 5),
            Err(KernelError::Range { .. })
        ));
        assert!(matches!(
            slice_md5(f.path(), u64::MAX, 2),
            Err(KernelError::Argument(_))
        ));
    }

    #[test]
    fn hash_bytes_and_hash_file_agree() {
        let data = b"panhub kernel";
        let f = temp_file(data);
        assert_eq!(hash_bytes(data), hash_file(f.path()).unwrap());
    }

    #[test]
    fn multi_block_file_stays_consistent() {
        // 跨过 256 KiB 缓冲边界，验证分块读取不会漏字节。
        let data = vec![0x5Au8; BUFFER_SIZE * 2 + 17];
        let f = temp_file(&data);
        let h = hash_file(f.path()).unwrap();
        assert_eq!(h.size, data.len() as u64);
        assert_eq!(h.md5, hex::encode(Md5::digest(&data)));
        assert_eq!(h.sha256, hex::encode(Sha256::digest(&data)));
    }
}
