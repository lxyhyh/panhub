//! PanHub 原生内核。
//!
//! 为什么需要 Rust：网盘客户端最重的活都在这一层——
//!  - 上传前要对整个文件算 MD5/SHA1/SHA256（秒传校验），
//!  - 大文件要按分片算 MD5（百度、123、阿里都有这套），
//!  - 下载后要校验完整性。
//! 这些操作在 Kotlin 里要么慢、要么得整块读进内存。放在 Rust 里可以
//! 流式读、单遍同时算多种摘要，并且对上层只暴露一个极窄的接口。
//!
//! 设计约束：
//!  - 所有函数都是纯函数或只读文件，不含全局可变状态；
//!  - 内存分配以块为单位，绝不把整个文件读进内存；
//!  - 错误统一映射成 Java 异常，不 panic 穿过 FFI 边界。

mod digest;
mod error;
mod jni_bridge;

pub use digest::{hash_bytes, hash_file, slice_md5, Hashes};
pub use error::KernelError;
