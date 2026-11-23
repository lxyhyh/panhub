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

/// 一次扫描算出的上传摘要。
///
/// 各网盘要的东西不一样，但都能从同一遍读盘里拿到：
///  - 123 云盘 / 坚果云只认整文件 MD5；
///  - 夸克要 MD5 + SHA-1；
///  - 百度要整文件 MD5 + 前 256 KiB 的 MD5 + 每个 4 MiB 分片的 MD5；
///  - 天翼要整文件 MD5 + 每个 10 MiB 分片的 MD5。
///
/// 分开扫四遍等于把几个 GB 的文件读四遍，在手机上要多花十几秒 IO。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct UploadScan {
    /// 整文件 MD5，小写十六进制。
    pub md5: String,
    /// 整文件 SHA-1，小写十六进制。
    pub sha1: String,
    /// 文件字节数。
    pub size: u64,
    /// 开头 prefix_limit 字节的 MD5；prefix_limit 为 0 时是空串。
    pub prefix_md5: String,
    /// 每个分片的 MD5，小写十六进制，按顺序排列。
    ///
    /// 最后一片不满时按实际字节算；空文件返回空列表。
    pub slice_md5s: Vec<String>,
}

/// 一遍读盘同时算整文件摘要、前缀摘要与逐分片摘要。
///
/// [slice_size] 为 0 时只算整文件与前缀摘要（不产生分片）。
pub fn scan_file(
    path: &Path,
    slice_size: u64,
    prefix_limit: u64,
) -> Result<UploadScan, KernelError> {
    let file = File::open(path)?;
    let size = file.metadata()?.len();
    let mut reader = BufReader::with_capacity(BUFFER_SIZE, file);
    let mut buf = vec![0u8; BUFFER_SIZE];

    let mut whole_md5 = Md5::new();
    let mut whole_sha1 = Sha1::new();
    let mut prefix = Md5::new();
    let mut prefix_remaining = prefix_limit;

    let mut slice = Md5::new();
    let mut slice_filled = 0u64;
    let mut slice_md5s: Vec<String> = Vec::new();

    loop {
        let read = reader.read(&mut buf)?;
        if read == 0 {
            break;
        }
        let chunk = &buf[..read];
        whole_md5.update(chunk);
        whole_sha1.update(chunk);

        if prefix_remaining > 0 {
            let take = std::cmp::min(prefix_remaining, read as u64) as usize;
            prefix.update(&chunk[..take]);
            prefix_remaining -= take as u64;
        }

        if slice_size > 0 {
            let mut offset = 0usize;
            while offset < read {
                let room = (slice_size - slice_filled) as usize;
                let take = std::cmp::min(room, read - offset);
                slice.update(&chunk[offset..offset + take]);
                offset += take;
                slice_filled += take as u64;
                if slice_filled == slice_size {
                    slice_md5s.push(hex::encode(slice.finalize_reset()));
                    slice_filled = 0;
                }
            }
        }
    }

    // 最后不满一片的尾巴也要记下来，否则服务端算出来的分片数会对不上。
    if slice_size > 0 && slice_filled > 0 {
        slice_md5s.push(hex::encode(slice.finalize_reset()));
    }

    Ok(UploadScan {
        md5: hex::encode(whole_md5.finalize()),
        sha1: hex::encode(whole_sha1.finalize()),
        size,
        prefix_md5: if prefix_limit == 0 {
            String::new()
        } else {
            hex::encode(prefix.finalize())
        },
        slice_md5s,
    })
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
            h.md5, "d41d8cd98f00b204e9800998ecf8427e",
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
        assert_eq!(
            slice_md5(f.path(), 10, 0).unwrap(),
            "d41d8cd98f00b204e9800998ecf8427e"
        );
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

    #[test]
    fn scan_reports_whole_prefix_and_slices_in_one_pass() {
        let f = temp_file(b"abc");
        let scan = scan_file(f.path(), 2, 2).unwrap();
        assert_eq!(scan.md5, "900150983cd24fb0d6963f7d28e17f72");
        assert_eq!(scan.sha1, "a9993e364706816aba3e25717850c26c9cd0d89d");
        assert_eq!(scan.size, 3);
        assert_eq!(scan.prefix_md5, hex::encode(Md5::digest(b"ab")));
        assert_eq!(
            scan.slice_md5s,
            vec![
                hex::encode(Md5::digest(b"ab")),
                hex::encode(Md5::digest(b"c")),
            ]
        );
    }

    #[test]
    fn scan_without_slice_size_skips_slices() {
        let f = temp_file(b"abcdefghij");
        let scan = scan_file(f.path(), 0, 0).unwrap();
        assert!(scan.slice_md5s.is_empty());
        assert!(scan.prefix_md5.is_empty());
        assert_eq!(scan.md5, hex::encode(Md5::digest(b"abcdefghij")));
    }

    #[test]
    fn scan_of_empty_file_is_well_known() {
        let f = temp_file(b"");
        let scan = scan_file(f.path(), 4, 8).unwrap();
        assert_eq!(scan.md5, "d41d8cd98f00b204e9800998ecf8427e");
        assert_eq!(scan.sha1, "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        assert_eq!(scan.size, 0);
        assert!(scan.slice_md5s.is_empty());
        assert_eq!(scan.prefix_md5, "d41d8cd98f00b204e9800998ecf8427e");
    }

    #[test]
    fn scan_prefix_longer_than_file_covers_whole_file() {
        let f = temp_file(b"abc");
        let scan = scan_file(f.path(), 0, 4096).unwrap();
        assert_eq!(scan.prefix_md5, scan.md5);
    }

    #[test]
    fn scan_slices_cross_buffer_boundaries() {
        // 分片大小不是缓冲区的整数分之一，强制「一次 read 跨多个分片」。
        let data: Vec<u8> = (0..(BUFFER_SIZE * 2 + 17))
            .map(|i| (i % 251) as u8)
            .collect();
        let f = temp_file(&data);
        let slice_size = 70_000u64;
        let scan = scan_file(f.path(), slice_size, 1000).unwrap();

        assert_eq!(scan.size, data.len() as u64);
        assert_eq!(scan.md5, hex::encode(Md5::digest(&data)));
        assert_eq!(scan.prefix_md5, hex::encode(Md5::digest(&data[..1000])));

        let expected: Vec<String> = data
            .chunks(slice_size as usize)
            .map(|chunk| hex::encode(Md5::digest(chunk)))
            .collect();
        assert_eq!(scan.slice_md5s, expected);
    }
}
