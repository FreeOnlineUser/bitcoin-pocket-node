/*
 * AGPL-3.0 License
 * Portions of this code are derived from mempool.space
 * https://github.com/mempool/mempool/tree/master/rust/gbt
 */

use jni::objects::JObject;
use jni::JNIEnv;

#[derive(Debug, Clone)]
pub struct ThreadTransaction {
    pub uid: u32,
    pub order: u32,
    pub fee: f64,
    pub weight: u32,
    pub sigops: u32,
    pub effective_fee_per_vsize: f64,
    pub inputs: Vec<u32>,
}

impl ThreadTransaction {
    /// Create a ThreadTransaction from a JNI object
    pub fn from_jni(env: &mut JNIEnv, obj: &JObject) -> jni::errors::Result<Self> {
        let uid = env.get_field(obj, "uid", "I")?.i()? as u32;
        let order = env.get_field(obj, "order", "I")?.i()? as u32;
        let fee = env.get_field(obj, "fee", "D")?.d()?;
        let weight = env.get_field(obj, "weight", "I")?.i()? as u32;
        let sigops = env.get_field(obj, "sigops", "I")?.i()? as u32;
        let effective_fee_per_vsize = env.get_field(obj, "effectiveFeePerVsize", "D")?.d()?;
        
        // Get inputs array
        let inputs_array = env.get_field(obj, "inputs", "[I")?.l()?;
        let inputs_len = env.get_array_length(&inputs_array.into())?;
        let mut inputs_buf = vec![0i32; inputs_len as usize];
        env.get_int_array_region(&inputs_array.into(), 0, &mut inputs_buf)?;
        let inputs = inputs_buf.into_iter().map(|i| i as u32).collect();

        Ok(ThreadTransaction {
            uid,
            order,
            fee,
            weight,
            sigops,
            effective_fee_per_vsize,
            inputs,
        })
    }
}