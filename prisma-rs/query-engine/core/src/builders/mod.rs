//! Query execution builders module
#![allow(warnings)]

/// I got sick of typing `Option<...>` over and over
macro_rules! opt {
    ($x:ty) => {
        Option<$x>
    };
}

mod many_rel;
mod multi;
mod one_rel;
mod single;

use graphql_parser::query::{Field, Selection, Value};
use inflector::Inflector;

use crate::{CoreError, CoreResult, PrismaQuery};
use connector::{filter::NodeSelector, QueryArguments};
use prisma_models::{
    Field as ModelField, ModelRef, OrderBy, PrismaValue, RelationFieldRef, SchemaRef, SelectedField, SelectedFields,
    SelectedScalarField, SortOrder,
};

use std::sync::Arc;

/// A common query-builder type
pub enum Builder<'field> {
    Single(single::Builder<'field>),
    Multi(multi::Builder<'field>),
    Rel(one_rel::Builder<'field>),
    ManyRel(many_rel::Builder<'field>),
}

impl<'a> Builder<'a> {
    /// Infer the type of builder that should be created
    fn infer(model: &ModelRef, field: &Field, parent: Option<RelationFieldRef>) -> Option<Self> {
        if let Some(ref parent) = parent {
            if parent.is_list {
                Some(Builder::ManyRel(many_rel::Builder::new()))
            } else {
                Some(Builder::Rel(one_rel::Builder::new()))
            }
        } else {
            if model.name.to_camel_case().to_singular() == field.name {
                Some(Builder::Single(single::Builder::new()))
            } else if model.name.to_camel_case().to_plural() == field.name {
                Some(Builder::Multi(multi::Builder::new()))
            } else {
                None
            }
        }
    }
}

/// FIXME: Do we want or need this?!
type BuilderResult<T> = Option<CoreResult<T>>;

/// A trait that describes a query builder
pub trait BuilderExt {
    type Output;

    /// A common cosntructor for all query builders
    fn new() -> Self;

    /// Last step that invokes query building
    fn build(self) -> CoreResult<Self::Output>;

    /// Get node selector from field and model
    fn extract_node_selector(field: &Field, model: ModelRef) -> CoreResult<NodeSelector> {
        // FIXME: this expects at least one query arg...
        let (_, value) = field.arguments.first().expect("no arguments found");
        match value {
            Value::Object(obj) => {
                let (field_name, value) = obj.iter().next().expect("object was empty");
                let field = model.fields().find_from_scalar(field_name).unwrap();
                let value = Self::value_to_prisma_value(value);

                Ok(NodeSelector {
                    field: Arc::clone(&field),
                    value: value,
                })
            }
            _ => unimplemented!(),
        }
    }

    /// Turning a GraphQL value to a PrismaValue
    fn value_to_prisma_value(val: &Value) -> PrismaValue {
        match val {
            Value::String(s) => PrismaValue::String(s.clone()),
            Value::Int(i) => PrismaValue::Int(i.as_i64().unwrap() as i32),
            _ => unimplemented!(),
        }
    }

    fn extract_query_args(field: &Field, model: ModelRef) -> CoreResult<QueryArguments> {
        field
            .arguments
            .iter()
            .fold(Ok(QueryArguments::default()), |result, (k, v)| {
                if let Ok(res) = result {
                    #[cfg_attr(rustfmt, rustfmt_skip)]
                    match (k.as_str(), v) {
                        ("skip", Value::Int(num)) => match num.as_i64() {
                            Some(num) => Ok(QueryArguments { skip: Some(num as u32), ..res }),
                            None => Err(CoreError::QueryValidationError("Invalid number povided".into())),
                        },
                        ("first", Value::Int(num)) => match num.as_i64() {
                            Some(num) => Ok(QueryArguments { first: Some(num as u32), ..res }),
                            None => Err(CoreError::QueryValidationError("Invalid number povided".into())),
                        },
                        ("last", Value::Int(num)) => match num.as_i64() {
                            Some(num) => Ok(QueryArguments { first: Some(num as u32), ..res }),
                            None => Err(CoreError::QueryValidationError("Invalid number povided".into())),
                        },
                        //("after", Value::String(s)) if s.is_uuid() => Ok(QueryArguments { after: Some(UuidString(s.clone()).into()), ..res }),
                        ("after", Value::String(s)) => Ok(QueryArguments { after: Some(s.clone().into()), ..res }),
                        ("after", Value::Int(num)) => match num.as_i64() {
                            Some(num) => Ok(QueryArguments { first: Some(num as u32), ..res }),
                            None => Err(CoreError::QueryValidationError("Invalid number povided".into())),
                        },
                        //("before", Value::String(s)) if s.is_uuid() => Ok(QueryArguments { before: Some(UuidString(s.clone()).into()), ..res }),
                        ("before", Value::String(s)) => Ok(QueryArguments { before: Some(s.clone().into()), ..res }),
                        ("before", Value::Int(num)) => match num.as_i64() {
                            Some(num) => Ok(QueryArguments { first: Some(num as u32), ..res }),
                            None => Err(CoreError::QueryValidationError("Invalid number povided".into())),
                        },
                        ("orderby", Value::Enum(name)) => {
                            let vec = name.split("_").collect::<Vec<&str>>();
                            if vec.len() == 2 {
                                model
                                    .fields()
                                    .find_from_scalar(vec[0])
                                    .map(|val| QueryArguments {
                                        order_by: Some(OrderBy {
                                            field: Arc::clone(&val),
                                            sort_order: match vec[1] {
                                                "ASC" => SortOrder::Ascending,
                                                "DESC" => SortOrder::Descending,
                                                _ => unreachable!(),
                                            },
                                        }),
                                        ..res
                                    })
                                    .map_err(|_| CoreError::QueryValidationError(format!("Unknown field `{}`", vec[0])))
                            } else {
                                Err(CoreError::QueryValidationError("...".into()))
                            }
                        }
                        ("where", _) => panic!("lolnope"),
                        (name, _) => Err(CoreError::QueryValidationError(format!("Unknown key: `{}`", name))),
                    }
                } else {
                    result
                }
            })
    }

    /// Get all selected fields from a model
    fn collect_selected_fields<I: Into<Option<RelationFieldRef>>>(
        model: ModelRef,
        field: &Field,
        parent: I,
    ) -> CoreResult<SelectedFields> {
        field
            .selection_set
            .items
            .iter()
            .filter_map(|i| {
                if let Selection::Field(f) = i {
                    // We have to make sure the selected field exists in some form.
                    let field = model.fields().find_from_all(&f.name);
                    match field {
                        Ok(ModelField::Scalar(field)) => Some(Ok(SelectedField::Scalar(SelectedScalarField {
                            field: Arc::clone(&field),
                            implicit: false,
                        }))),
                        // Relation fields are not handled here, but in nested queries
                        Ok(ModelField::Relation(_field)) => None,
                        _ => Some(Err(CoreError::QueryValidationError(format!(
                            "Selected field {} not found on model {}",
                            f.name, model.name,
                        )))),
                    }
                } else {
                    // Todo: We only support selecting fields at the moment.
                    unimplemented!()
                }
            })
            .collect::<CoreResult<Vec<_>>>()
            .map(|sf| SelectedFields::new(sf, parent.into()))
    }

    fn collect_nested_queries<'field>(
        model: ModelRef,
        ast_field: &'field Field,
        schema: SchemaRef,
    ) -> CoreResult<Vec<Builder<'field>>> {
        ast_field
            .selection_set
            .items
            .iter()
            .filter_map(|i| {
                if let Selection::Field(f) = i {
                    let field = model.fields().find_from_all(&f.name);
                    match field {
                        Ok(ModelField::Scalar(_f)) => None,
                        Ok(ModelField::Relation(f)) => {
                            let model = f.related_model();
                            let parent = Some(Arc::clone(&f));

                            match Builder::infer(&model, &ast_field, parent) {
                                Some(Builder::Rel(b)) => {
                                    Some(Ok(Builder::Rel(b.setup(model, ast_field, Arc::clone(f)))))
                                }
                                Some(Builder::ManyRel(b)) => {
                                    Some(Ok(Builder::ManyRel(b.setup(model, ast_field, Arc::clone(f)))))
                                }
                                _ => None,
                            }
                        }
                        _ => Some(Err(CoreError::QueryValidationError(format!(
                            "Selected field {} not found on model {}",
                            f.name, model.name,
                        )))),
                    }
                } else {
                    panic!("We only support selecting fields at the moment!");
                }
            })
            .collect()
    }

    fn build_nested_queries(builders: Vec<Builder>) -> CoreResult<Vec<PrismaQuery>> {
        builders
            .into_iter()
            .map(|b| match b {
                Builder::Rel(builder) => unimplemented!(),
                Builder::ManyRel(builder) => unimplemented!(),
                _ => unreachable!(),
            })
            .collect()
    }
}
