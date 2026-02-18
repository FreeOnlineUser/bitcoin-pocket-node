/*
 * AGPL-3.0 License
 * Portions of this code are derived from mempool.space
 * https://github.com/mempool/mempool/tree/master/rust/gbt
 */

use priority_queue::PriorityQueue;
use std::{
    collections::{HashMap, HashSet},
    fmt::Debug,
    hash::{BuildHasher, Hasher},
};

/// This is the only way to create a `HashMap` with the `U32HasherState` and capacity
pub fn u32hashmap_with_capacity<V>(capacity: usize) -> HashMap<u32, V, U32HasherState> {
    HashMap::with_capacity_and_hasher(capacity, U32HasherState(()))
}

/// This is the only way to create a `PriorityQueue` with the `U32HasherState` and capacity
pub fn u32priority_queue_with_capacity<V: Ord>(
    capacity: usize,
) -> PriorityQueue<u32, V, U32HasherState> {
    PriorityQueue::with_capacity_and_hasher(capacity, U32HasherState(()))
}

/// This is the only way to create a `HashSet` with the `U32HasherState`
pub fn u32hashset_new() -> HashSet<u32, U32HasherState> {
    HashSet::with_hasher(U32HasherState(()))
}

/// A private unit type is contained so no one can make an instance of it.
#[derive(Clone)]
pub struct U32HasherState(());

impl Debug for U32HasherState {
    fn fmt(&self, _: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        Ok(())
    }
}

impl BuildHasher for U32HasherState {
    type Hasher = U32Hasher;

    fn build_hasher(&self) -> Self::Hasher {
        U32Hasher(0)
    }
}

/// This also can't be created outside this module due to private field.
pub struct U32Hasher(u32);

impl Hasher for U32Hasher {
    fn finish(&self) -> u64 {
        // Safety: Two u32s next to each other will make a u64
        bytemuck::cast([self.0, 0])
    }

    fn write(&mut self, bytes: &[u8]) {
        // Assert in debug builds (testing too) that only 4 byte keys (u32, i32, f32, etc.) run
        debug_assert!(bytes.len() == 4);
        // Safety: We know that the size of the key is 4 bytes
        // We also know that the only way to get an instance of HashMap using this "hasher"
        // is through the public functions in this module which set the key type to u32.
        self.0 = *bytemuck::from_bytes(bytes);
    }
}