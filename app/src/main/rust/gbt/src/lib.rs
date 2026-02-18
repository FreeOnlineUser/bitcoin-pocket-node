/*
 * AGPL-3.0 License
 * Portions of this code are derived from mempool.space
 * https://github.com/mempool/mempool/tree/master/rust/gbt
 */

#![warn(clippy::all)]
#![warn(clippy::pedantic)]
#![warn(clippy::nursery)]
#![allow(clippy::cast_precision_loss)]
#![allow(clippy::cast_possible_truncation)]
#![allow(clippy::cast_sign_loss)]
#![allow(clippy::float_cmp)]

use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{jlong, jobjectArray, jstring};
use jni::JNIEnv;
use thread_transaction::ThreadTransaction;
use thread_acceleration::ThreadAcceleration;
use tracing::{debug, info, trace};
use tracing_log::LogTracer;
use tracing_subscriber::{EnvFilter, FmtSubscriber};

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

mod audit_transaction;
mod gbt;
mod thread_transaction;
mod thread_acceleration;
mod u32_hasher_types;

use u32_hasher_types::{u32hashmap_with_capacity, U32HasherState};

/// This is the initial capacity of the `GbtGenerator` struct's inner `HashMap`.
///
/// Note: This doesn't *have* to be a power of 2. (uwu)
const STARTING_CAPACITY: usize = 1_048_576;

type ThreadTransactionsMap = HashMap<u32, ThreadTransaction, U32HasherState>;

pub struct GbtGenerator {
    thread_transactions: Arc<Mutex<ThreadTransactionsMap>>,
    max_block_weight: u32,
    max_blocks: usize,
}

/// The result from calling the gbt function.
///
/// This tuple contains the following:
///        blocks: A 2D Vector of transaction IDs (u32), the inner Vecs each represent a block.
/// block_weights: A Vector of total weights per block.
///      clusters: A 2D Vector of transaction IDs representing clusters of dependent mempool transactions
///         rates: A Vector of tuples containing transaction IDs (u32) and effective fee per vsize (f64)
pub struct GbtResult {
    pub blocks: Vec<Vec<u32>>,
    pub block_weights: Vec<u32>,
    pub clusters: Vec<Vec<u32>>,
    pub rates: Vec<Vec<f64>>, // Tuples not supported. u32 fits inside f64
    pub overflow: Vec<u32>,
}

impl GbtResult {
    /// Convert GbtResult to JNI object
    pub fn to_jni(&self, env: &mut JNIEnv) -> jni::errors::Result<JObject> {
        // Create the result object
        let gbt_result_class = env.find_class("com/pocketnode/mempool/GbtResult")?;
        let constructor = env.get_method_id(&gbt_result_class, "<init>", "()V")?;
        let result_obj = env.new_object_unchecked(&gbt_result_class, constructor, &[])?;

        // Convert blocks to Java int[][]
        let blocks_array = env.new_object_array(
            self.blocks.len() as i32,
            "[I",
            JObject::null(),
        )?;
        
        for (i, block) in self.blocks.iter().enumerate() {
            let block_array = env.new_int_array(block.len() as i32)?;
            let block_ints: Vec<i32> = block.iter().map(|&uid| uid as i32).collect();
            env.set_int_array_region(&block_array, 0, &block_ints)?;
            env.set_object_array_element(&blocks_array, i as i32, JObject::from(block_array))?;
        }
        env.set_field(&result_obj, "blocks", "[[I", JObject::from(blocks_array))?;

        // Convert block_weights to Java int[]
        let weights_array = env.new_int_array(self.block_weights.len() as i32)?;
        let weights_ints: Vec<i32> = self.block_weights.iter().map(|&w| w as i32).collect();
        env.set_int_array_region(&weights_array, 0, &weights_ints)?;
        env.set_field(&result_obj, "blockWeights", "[I", JObject::from(weights_array))?;

        // Convert clusters to Java int[][]
        let clusters_array = env.new_object_array(
            self.clusters.len() as i32,
            "[I",
            JObject::null(),
        )?;
        
        for (i, cluster) in self.clusters.iter().enumerate() {
            let cluster_array = env.new_int_array(cluster.len() as i32)?;
            let cluster_ints: Vec<i32> = cluster.iter().map(|&uid| uid as i32).collect();
            env.set_int_array_region(&cluster_array, 0, &cluster_ints)?;
            env.set_object_array_element(&clusters_array, i as i32, JObject::from(cluster_array))?;
        }
        env.set_field(&result_obj, "clusters", "[[I", JObject::from(clusters_array))?;

        // Convert rates to Java double[][]
        let rates_array = env.new_object_array(
            self.rates.len() as i32,
            "[D",
            JObject::null(),
        )?;
        
        for (i, rate) in self.rates.iter().enumerate() {
            let rate_array = env.new_double_array(rate.len() as i32)?;
            env.set_double_array_region(&rate_array, 0, rate)?;
            env.set_object_array_element(&rates_array, i as i32, JObject::from(rate_array))?;
        }
        env.set_field(&result_obj, "rates", "[[D", JObject::from(rates_array))?;

        // Convert overflow to Java int[]
        let overflow_array = env.new_int_array(self.overflow.len() as i32)?;
        let overflow_ints: Vec<i32> = self.overflow.iter().map(|&uid| uid as i32).collect();
        env.set_int_array_region(&overflow_array, 0, &overflow_ints)?;
        env.set_field(&result_obj, "overflow", "[I", JObject::from(overflow_array))?;

        Ok(result_obj)
    }
}

// Initialize logging
#[no_mangle]
pub extern "C" fn Java_com_pocketnode_mempool_GbtGenerator_initNative(
    _env: JNIEnv,
    _class: JClass,
) {
    // Set all `tracing` logs to print to STDOUT
    // Note: Passing RUST_LOG env variable to the Android process
    //       will change the log level for the rust module.
    let _ = tracing::subscriber::set_global_default(
        FmtSubscriber::builder()
            .with_env_filter(EnvFilter::from_default_env())
            .with_ansi(false) // Android doesn't support ANSI colors
            .finish(),
    );
    // Convert all `log` logs into `tracing` events
    let _ = LogTracer::init();
}

// Create a new GbtGenerator
#[no_mangle]
pub extern "C" fn Java_com_pocketnode_mempool_GbtGenerator_createNative(
    _env: JNIEnv,
    _class: JClass,
    max_block_weight: u32,
    max_blocks: u32,
) -> jlong {
    debug!("Created new GbtGenerator");
    let generator = Box::new(GbtGenerator {
        thread_transactions: Arc::new(Mutex::new(u32hashmap_with_capacity(STARTING_CAPACITY))),
        max_block_weight,
        max_blocks: max_blocks as usize,
    });
    Box::into_raw(generator) as jlong
}

// Destroy GbtGenerator
#[no_mangle]
pub extern "C" fn Java_com_pocketnode_mempool_GbtGenerator_destroyNative(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    if ptr != 0 {
        let _generator = unsafe { Box::from_raw(ptr as *mut GbtGenerator) };
        // Drop happens automatically
    }
}

// Run GBT with initial mempool
#[no_mangle]
pub extern "C" fn Java_com_pocketnode_mempool_GbtGenerator_makeNative(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    mempool_array: JObjectArray,
    accelerations_array: JObjectArray,
    max_uid: u32,
) -> jobjectArray {
    let generator = unsafe { &*(ptr as *mut GbtGenerator) };
    
    // Parse mempool transactions
    let mempool_len = env.get_array_length(&mempool_array).unwrap_or(0) as usize;
    let mut mempool = Vec::with_capacity(mempool_len);
    
    for i in 0..mempool_len {
        if let Ok(obj) = env.get_object_array_element(&mempool_array, i as i32) {
            if let Ok(tx) = ThreadTransaction::from_jni(&mut env, &obj) {
                mempool.push(tx);
            }
        }
    }

    // Parse accelerations
    let acc_len = env.get_array_length(&accelerations_array).unwrap_or(0) as usize;
    let mut accelerations = Vec::with_capacity(acc_len);
    
    for i in 0..acc_len {
        if let Ok(obj) = env.get_object_array_element(&accelerations_array, i as i32) {
            if let Ok(acc) = ThreadAcceleration::from_jni(&mut env, &obj) {
                accelerations.push(acc);
            }
        }
    }

    // Run GBT
    match run_gbt(
        Arc::clone(&generator.thread_transactions),
        accelerations,
        max_uid as usize,
        generator.max_block_weight,
        generator.max_blocks,
        move |map| {
            for tx in mempool {
                map.insert(tx.uid, tx);
            }
        },
    ) {
        Ok(result) => {
            match result.to_jni(&mut env) {
                Ok(obj) => obj.into_raw(),
                Err(_) => JObject::null().into_raw(),
            }
        }
        Err(_) => JObject::null().into_raw(),
    }
}

// Update GBT with new/removed transactions
#[no_mangle]
pub extern "C" fn Java_com_pocketnode_mempool_GbtGenerator_updateNative(
    mut env: JNIEnv,
    _class: JClass,
    ptr: jlong,
    new_txs_array: JObjectArray,
    remove_txs_array: JObjectArray,
    accelerations_array: JObjectArray,
    max_uid: u32,
) -> jobjectArray {
    let generator = unsafe { &*(ptr as *mut GbtGenerator) };
    
    // Parse new transactions
    let new_len = env.get_array_length(&new_txs_array).unwrap_or(0) as usize;
    let mut new_txs = Vec::with_capacity(new_len);
    
    for i in 0..new_len {
        if let Ok(obj) = env.get_object_array_element(&new_txs_array, i as i32) {
            if let Ok(tx) = ThreadTransaction::from_jni(&mut env, &obj) {
                new_txs.push(tx);
            }
        }
    }

    // Parse remove transactions
    let remove_len = env.get_array_length(&remove_txs_array).unwrap_or(0) as usize;
    let mut remove_txs = Vec::with_capacity(remove_len);
    
    for i in 0..remove_len {
        if let Ok(obj) = env.get_object_array_element(&remove_txs_array, i as i32) {
            if let Ok(uid_obj) = env.call_method(&obj, "intValue", "()I", &[]) {
                if let Ok(uid) = uid_obj.i() {
                    remove_txs.push(uid as u32);
                }
            }
        }
    }

    // Parse accelerations
    let acc_len = env.get_array_length(&accelerations_array).unwrap_or(0) as usize;
    let mut accelerations = Vec::with_capacity(acc_len);
    
    for i in 0..acc_len {
        if let Ok(obj) = env.get_object_array_element(&accelerations_array, i as i32) {
            if let Ok(acc) = ThreadAcceleration::from_jni(&mut env, &obj) {
                accelerations.push(acc);
            }
        }
    }

    // Run GBT with updates
    match run_gbt(
        Arc::clone(&generator.thread_transactions),
        accelerations,
        max_uid as usize,
        generator.max_block_weight,
        generator.max_blocks,
        move |map| {
            for tx in new_txs {
                map.insert(tx.uid, tx);
            }
            for txid in &remove_txs {
                map.remove(txid);
            }
        },
    ) {
        Ok(result) => {
            match result.to_jni(&mut env) {
                Ok(obj) => obj.into_raw(),
                Err(_) => JObject::null().into_raw(),
            }
        }
        Err(_) => JObject::null().into_raw(),
    }
}

/// Run GBT algorithm in a separate task
fn run_gbt<F>(
    thread_transactions: Arc<Mutex<ThreadTransactionsMap>>,
    accelerations: Vec<ThreadAcceleration>,
    max_uid: usize,
    max_block_weight: u32,
    max_blocks: usize,
    callback: F,
) -> Result<GbtResult, String>
where
    F: FnOnce(&mut ThreadTransactionsMap) + Send + 'static,
{
    debug!("Getting lock for thread_transactions...");
    let mut map = thread_transactions
        .lock()
        .map_err(|_| "THREAD_TRANSACTIONS Mutex poisoned")?;
    callback(&mut map);

    info!("Starting gbt algorithm for {} elements...", map.len());
    let result = gbt::gbt(
        &mut map,
        &accelerations,
        max_uid,
        max_block_weight,
        max_blocks,
    );
    info!("Finished gbt algorithm for {} elements...", map.len());

    debug!("Releasing lock for thread_transactions...");
    drop(map);

    Ok(result)
}