/*
 * AGPL-3.0 License
 * Portions of this code are derived from mempool.space
 * https://github.com/mempool/mempool/tree/master/rust/gbt
 */

use jni::objects::JObject;
use jni::JNIEnv;

#[derive(Debug, Clone)]
pub struct ThreadAcceleration {
    pub uid: u32,
    pub delta: f64, // fee delta
}

impl ThreadAcceleration {
    /// Create a ThreadAcceleration from a JNI object
    pub fn from_jni(env: &mut JNIEnv, obj: &JObject) -> jni::errors::Result<Self> {
        let uid = env.get_field(obj, "uid", "I")?.i()? as u32;
        let delta = env.get_field(obj, "delta", "D")?.d()?;

        Ok(ThreadAcceleration { uid, delta })
    }
}