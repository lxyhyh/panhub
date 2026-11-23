//! JNI 边界。
//!
//! 只做三件事：把 Java 字符串/数组转成 Rust 值、调用 core 逻辑、把错误转成
//! Java 异常。任何业务判断都不在这里。
//!
//! 函数名遵循 JNI 规范：Java_com_panhub_core_kernel_NativeKernel_<method>。
//!
//! jni 0.22 起 `JNIEnv` 拆成 `Env`（已附着线程的句柄）与 `EnvUnowned`
//! （原生方法收到的裸 JNIEnv 指针）。约定：
//!  - 原生方法第一个参数是 `EnvUnowned<'caller>`；
//!  - 所有 JNI 调用放进 `with_env` 的闭包（内部 catch_unwind，panic 不会
//!    穿过 FFI 把进程 abort）；
//!  - 用 `resolve::<ThrowRuntimeExAndDefault>()` 把 Rust 错误转成
//!    java.lang.RuntimeException，并返回 null 作为默认值。

use crate::{digest, error::KernelError, Hashes, UploadScan};
use jni::errors::{Error as JniError, ThrowRuntimeExAndDefault};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jlong, jstring};
use jni::EnvUnowned;

/// JNI 边界上的错误：要么来自业务逻辑，要么来自 JNI 调用本身。
///
/// 单独定义一个类型（而不是给 [KernelError] 加 jni 变体），是为了让
/// core 层的错误类型保持与 JNI 无关，方便在纯 Rust 测试里使用。
#[derive(Debug, thiserror::Error)]
enum BridgeError {
    #[error("{0}")]
    Kernel(#[from] KernelError),

    #[error("JNI 调用失败：{0}")]
    Jni(#[from] JniError),
}

/// 把 [Hashes] 序列化成一行 JSON，避免定义复杂 Java 类。
/// 固定字段名，Kotlin 侧解析成本几乎为零。
fn hashes_to_json(h: &Hashes) -> String {
    format!(
        "{{\"md5\":\"{}\",\"sha1\":\"{}\",\"sha256\":\"{}\",\"crc32\":{},\"size\":{}}}",
        h.md5, h.sha1, h.sha256, h.crc32, h.size
    )
}

/// 统一的返回值出口：闭包算出 String，出错就抛 Java 异常并回 null。
fn return_string<'caller, F>(unowned: &mut EnvUnowned<'caller>, work: F) -> jstring
where
    F: FnOnce(&mut jni::Env<'caller>) -> Result<String, BridgeError>,
{
    let created: Option<JString<'caller>> = unowned
        .with_env(
            move |env| -> Result<Option<JString<'caller>>, BridgeError> {
                let value = work(env)?;
                let jstr = env.new_string(value)?;
                Ok(Some(jstr))
            },
        )
        .resolve::<ThrowRuntimeExAndDefault>();

    match created {
        Some(s) => s.into_raw(),
        None => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_panhub_core_kernel_NativeKernel_hashFile<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    path: JString<'caller>,
) -> jstring {
    return_string(&mut unowned_env, move |env| {
        let path_str = path.try_to_string(env)?;
        let hashes = digest::hash_file(std::path::Path::new(&path_str))?;
        Ok(hashes_to_json(&hashes))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_panhub_core_kernel_NativeKernel_hashBytes<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    data: JByteArray<'caller>,
) -> jstring {
    return_string(&mut unowned_env, move |env| {
        let bytes = env.convert_byte_array(&data)?;
        Ok(hashes_to_json(&digest::hash_bytes(&bytes)))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_panhub_core_kernel_NativeKernel_sliceMd5<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    path: JString<'caller>,
    offset: jlong,
    length: jlong,
) -> jstring {
    return_string(&mut unowned_env, move |env| {
        if offset < 0 || length < 0 {
            return Err(KernelError::Argument("offset/length 不能为负".into()).into());
        }
        let path_str = path.try_to_string(env)?;
        let md5 = digest::slice_md5(
            std::path::Path::new(&path_str),
            offset as u64,
            length as u64,
        )?;
        Ok(md5)
    })
}

/// 把 [UploadScan] 序列化成一行 JSON。
///
/// 分片列表可能很长（几个 GB 的文件有上千片），所以用 String 拼接而不是
/// 引入 JSON 库：内核里所有字段都是十六进制串，不需要转义。
fn scan_to_json(s: &UploadScan) -> String {
    let slices = s
        .slice_md5s
        .iter()
        .map(|md5| format!("\"{md5}\""))
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "{{\"md5\":\"{}\",\"sha1\":\"{}\",\"size\":{},\"prefixMd5\":\"{}\",\"sliceMd5s\":[{}]}}",
        s.md5, s.sha1, s.size, s.prefix_md5, slices
    )
}

/// 一遍读盘算出上传需要的全部摘要。
///
/// sliceSize 传 0 表示不算分片；prefixLimit 传 0 表示不算前缀摘要。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_panhub_core_kernel_NativeKernel_scanFile<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    path: JString<'caller>,
    slice_size: jlong,
    prefix_limit: jlong,
) -> jstring {
    return_string(&mut unowned_env, move |env| {
        if slice_size < 0 || prefix_limit < 0 {
            return Err(KernelError::Argument("sliceSize/prefixLimit 不能为负".into()).into());
        }
        let path_str = path.try_to_string(env)?;
        let scan = digest::scan_file(
            std::path::Path::new(&path_str),
            slice_size as u64,
            prefix_limit as u64,
        )?;
        Ok(scan_to_json(&scan))
    })
}

/// 内核版本号，用来确认 .so 与 Kotlin 侧是否同批构建。
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_panhub_core_kernel_NativeKernel_kernelVersion<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> jstring {
    return_string(&mut unowned_env, |_env| {
        Ok(env!("CARGO_PKG_VERSION").to_string())
    })
}
