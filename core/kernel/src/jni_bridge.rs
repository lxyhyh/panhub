//! JNI 边界。
//!
//! 只做三件事：把 Java 字符串/数组转成 Rust 值、调用 core 逻辑、把错误转成
//! Java 异常。任何业务判断都不在这里。
//!
//! 函数名遵循 JNI 规范：Java_com_panhub_core_kernel_NativeKernel_<method>。
//! 注意：jni crate 0.22 起 `JNIEnv` 的方法要 `&mut`，JString 不再有 `into()`
//! 直接转 String，统一走 `env.get_string(...)?.to_string_lossy()`。

use crate::{digest, error::KernelError, Hashes};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

/// 把 Rust 的 Result 落成 Java 返回值或抛出 Java 异常。
fn finish<T>(
    env: &mut JNIEnv,
    result: Result<T, KernelError>,
    on_ok: impl FnOnce(&mut JNIEnv, T) -> jstring,
) -> jstring {
    match result {
        Ok(value) => on_ok(env, value),
        Err(err) => {
            let _ = env.throw_new("java/lang/RuntimeException", err.to_string());
            std::ptr::null_mut()
        }
    }
}

/// 把 [Hashes] 序列化成一行 JSON，避免定义复杂 Java 类。
/// 固定字段名，Kotlin 侧解析成本几乎为零。
fn hashes_to_json(h: &Hashes) -> String {
    format!(
        "{{\"md5\":\"{}\",\"sha1\":\"{}\",\"sha256\":\"{}\",\"crc32\":{},\"size\":{}}}",
        h.md5, h.sha1, h.sha256, h.crc32, h.size
    )
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> Result<String, KernelError> {
    env.get_string(s)
        .map(|v| v.to_string_lossy())
        .map_err(|e| KernelError::Argument(format!("无法读取字符串参数：{e}")))
}

#[no_mangle]
pub extern "system" fn Java_com_panhub_core_kernel_NativeKernel_hashFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let result = jstring_to_string(&mut env, &path)
        .and_then(|p| digest::hash_file(std::path::Path::new(&p)));

    let json = match result {
        Ok(hashes) => hashes_to_json(&hashes),
        Err(err) => {
            let _ = env.throw_new("java/lang/RuntimeException", err.to_string());
            return std::ptr::null_mut();
        }
    };

    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_panhub_core_kernel_NativeKernel_hashBytes(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jstring {
    let bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException", format!("无法读取字节数组：{e}"));
            return std::ptr::null_mut();
        }
    };

    let json = hashes_to_json(&digest::hash_bytes(&bytes));
    match env.new_string(json) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_panhub_core_kernel_NativeKernel_sliceMd5(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
    offset: jlong,
    length: jlong,
) -> jstring {
    let result = (|| -> Result<String, KernelError> {
        if offset < 0 || length < 0 {
            return Err(KernelError::Argument("offset/length 不能为负".into()));
        }
        let path_str = jstring_to_string(&mut env, &path)?;
        digest::slice_md5(
            std::path::Path::new(&path_str),
            offset as u64,
            length as u64,
        )
    })();

    let md5 = match result {
        Ok(v) => v,
        Err(err) => {
            let _ = env.throw_new("java/lang/RuntimeException", err.to_string());
            return std::ptr::null_mut();
        }
    };

    match env.new_string(md5) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// 内核版本号，用来确认 .so 与 Kotlin 侧是否同批构建。
#[no_mangle]
pub extern "system" fn Java_com_panhub_core_kernel_NativeKernel_kernelVersion(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    match env.new_string(env!("CARGO_PKG_VERSION")) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
