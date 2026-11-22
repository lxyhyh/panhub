use thiserror::Error;

/// 内核的错误类型。到 JNI 边界时统一转成 RuntimeException。
#[derive(Debug, Error)]
pub enum KernelError {
    #[error("读取文件失败：{0}")]
    Io(#[from] std::io::Error),

    #[error("分片范围非法：offset={offset}, length={length}, file_size={file_size}")]
    Range {
        offset: u64,
        length: u64,
        file_size: u64,
    },

    #[error("参数非法：{0}")]
    Argument(String),
}
