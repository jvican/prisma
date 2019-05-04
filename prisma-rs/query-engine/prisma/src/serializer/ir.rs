//! Serializer Intermediate Representation
//!
//! Flexible intermediate representation for `PrismaQueryResult`s
//! which associates data from subsequent chained and nested queries
//! correctly.
//!
//! In the main `PrismaQueraResult` DSL, there's no trivial way of
//! associating data from a nested multi-query with a parent.
//! This IR fixes that issue, allowing us to serialize to various
//! flexible formats.

use core::{MultiPrismaQueryResult, PrismaQueryResult, SinglePrismaQueryResult};
use prisma_models::PrismaValue;
use serde::Serialize;
use std::collections::BTreeMap;

/// A set of responses to provided queries
pub type Responses = Vec<IrResponse>;

#[allow(dead_code)]
pub enum IrResponse {
    Data(Item),
    Error(String), // TODO: Get a better error kind?
}

/// A key -> value map to an IR item
pub type Map = BTreeMap<String, Item>;

/// A list of IR items
pub type List = Vec<Item>;

/// An IR item that either expands to a subtype or leaf-node
#[derive(Serialize)] // TODO: REMOVE AGAIN
pub enum Item {
    Map(Map),
    List(List),
    Value(PrismaValue),
}

/// A serialization IR builder utility
pub struct IrBuilder<'results>(Vec<&'results PrismaQueryResult>);

impl<'results> IrBuilder<'results> {
    pub fn new() -> Self {
        Self(vec![])
    }

    /// Add a single query result to the builder
    pub fn add(mut self, q: &'results PrismaQueryResult) -> Self {
        self.0.push(q);
        self
    }

    /// Parse collected queries into a wrapper type
    pub fn build(self) -> Responses {
        self.0.into_iter().fold(vec![], |mut vec, res| {
            vec.push(match res {
                PrismaQueryResult::Single(query) => IrResponse::Data(Item::Map(build_map(query))),
                PrismaQueryResult::Multi(query) => IrResponse::Data(Item::List(build_list(query))),
            });
            vec
        })
    }
}

fn build_map(result: &SinglePrismaQueryResult) -> Map {
    // Build selected fields first
    let outer = match &result.result {
        Some(single) => single
            .field_names
            .iter()
            .zip(&single.node.values)
            .fold(Map::new(), |mut map, (name, val)| {
                map.insert(name.clone(), Item::Value(val.clone()));
                map
            }),
        None => panic!("No result found"), // FIXME: Can this ever happen?
    };

    // Then add nested selected fields
    result.nested.iter().fold(outer, |mut map, query| {
        match query {
            PrismaQueryResult::Single(nested) => map.insert(nested.name.clone(), Item::Map(build_map(nested))),
            PrismaQueryResult::Multi(nested) => map.insert(nested.name.clone(), Item::List(build_list(nested))),
        };

        map
    })
}

fn build_list(result: &MultiPrismaQueryResult) -> List {
    let mut vec: Vec<Item> = result
        .result
        .as_pairs()
        .iter()
        .map(|vec| {
            Item::Map(vec.iter().fold(Map::new(), |mut map, (name, value)| {
                map.insert(name.clone(), Item::Value(value.clone()));
                map
            }))
        })
        .collect();

    result.nested.iter().zip(&mut vec).for_each(|(nested, map)| {
        match map {
            Item::Map(ref mut map) => match nested {
                PrismaQueryResult::Single(nested) => map.insert(nested.name.clone(), Item::Map(build_map(nested))),
                PrismaQueryResult::Multi(nested) => map.insert(nested.name.clone(), Item::List(build_list(nested))),
            },
            _ => unreachable!(),
        };
    });

    vec
}
