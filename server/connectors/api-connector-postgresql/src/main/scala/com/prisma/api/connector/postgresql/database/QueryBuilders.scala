package com.prisma.api.connector.postgresql.database

import java.sql.PreparedStatement

import com.prisma.api.connector._
import com.prisma.api.connector.postgresql.database.PostgresSlickExtensions._
import com.prisma.api.schema.APIErrors
import com.prisma.api.schema.APIErrors.{InvalidFirstArgument, InvalidLastArgument, InvalidSkipArgument}
import com.prisma.gc_values.{GCValue, NullGCValue, StringGCValue}
import com.prisma.shared.models._
import slick.jdbc.{PositionedParameters, SQLActionBuilder}

object QueryBuilders {
  def model(schemaName: String, model: Model, args: Option[QueryArguments]): QueryBuilder            = ModelQueryBuilder(schemaName, model, args)
  def relation(schemaName: String, relation: Relation, args: Option[QueryArguments]): QueryBuilder   = RelationQueryBuilder(schemaName, relation, args)
  def scalarList(schemaName: String, field: ScalarField, args: Option[QueryArguments]): QueryBuilder = ScalarListQueryBuilder(schemaName, field, args)
  def count(schemaName: String, table: String, filter: Option[Filter]): QueryBuilder                 = CountQueryBuilder(schemaName, table, filter)
}

trait QueryBuilder {
  def queryString: String
  def setParamsForQueryArgs(preparedStatement: PreparedStatement, queryArguments: Option[QueryArguments]): Unit = {
    SetParams.setQueryArgs(preparedStatement, queryArguments)
  }

  def setParamsForFilter(preparedStatement: PreparedStatement, filter: Option[Filter]): Unit = {
    SetParams.setFilter(preparedStatement, filter)
  }
}

case class RelationQueryBuilder(schemaName: String, relation: Relation, queryArguments: Option[QueryArguments]) extends QueryBuilder {
  val topLevelAlias = "Alias"

  lazy val queryString: String = {
    val tableName = relation.relationTableName
    s"""SELECT * FROM "$schemaName"."$tableName" AS "$topLevelAlias" """ +
      WhereClauseBuilder(schemaName).buildWhereClause(queryArguments.flatMap(_.filter)).getOrElse("") +
      OrderByClauseBuilder.forRelation(relation, topLevelAlias, queryArguments) +
      LimitClauseBuilder.limitClause(queryArguments)
  }
}

case class CountQueryBuilder(schemaName: String, table: String, filter: Option[Filter]) extends QueryBuilder {
  val topLevelAlias = "Alias"

  lazy val queryString: String = {
    s"""SELECT COUNT(*) FROM "$schemaName"."$table" AS "$topLevelAlias" """ +
      WhereClauseBuilder(schemaName).buildWhereClause(filter).getOrElse("")
  }
}

case class ScalarListQueryBuilder(schemaName: String, field: ScalarField, queryArguments: Option[QueryArguments]) extends QueryBuilder {
  require(field.isList, "This must be called only with scalar list fields")
  val topLevelAlias = "Alias"

  lazy val queryString: String = {
    val tableName = s"${field.model.dbName}_${field.dbName}"
    s"""SELECT * FROM "$schemaName"."$tableName" AS "$topLevelAlias" """ +
      WhereClauseBuilder(schemaName).buildWhereClause(queryArguments.flatMap(_.filter)).getOrElse("") +
      OrderByClauseBuilder.forScalarListField(field, topLevelAlias, queryArguments) +
      LimitClauseBuilder.limitClause(queryArguments)
  }
}

//case class CountRelatedModelsQueryBuilder(schemaName: String, fromField: RelationField, queryArguments: Option[QueryArguments]){
//
//}

case class RelatedModelsQueryBuilder(schemaName: String, fromField: RelationField, queryArguments: Option[QueryArguments]) extends QueryBuilder {
  val ALIAS                   = "Alias"
  val relation                = fromField.relation
  val modelRelationSideColumn = relation.columnForRelationSide(fromField.relationSide)
  val fieldRelationSideColumn = relation.columnForRelationSide(fromField.oppositeRelationSide)

  lazy val queryString: String = buildQuery(modelRelationSideColumn, fieldRelationSideColumn)
  val queryStringFromOtherSide = buildQuery(fieldRelationSideColumn, modelRelationSideColumn)

  private def buildQuery(modelRelationSideColumn: String, fieldRelationSideColumn: String): String = {
    val relation              = fromField.relation
    val relatedModel          = fromField.relatedModel_!
    val modelTable            = relatedModel.dbName
    val relationTableName     = fromField.relation.relationTableName
    val aColumn               = relation.modelAColumn
    val bColumn               = relation.modelBColumn
    val columnForRelatedModel = relation.columnForRelationSide(fromField.oppositeRelationSide)

    s"""select "$ALIAS".*, "RelationTable"."$aColumn" as "__Relation__A",  "RelationTable"."$bColumn" as "__Relation__B"
            from "$schemaName"."$modelTable" as "$ALIAS"
            inner join "$schemaName"."$relationTableName" as "RelationTable"
            on "$ALIAS"."${relatedModel.dbNameOfIdField_!}" = "RelationTable"."$fieldRelationSideColumn"
            where "RelationTable"."$modelRelationSideColumn" = ? AND """ +
      WhereClauseBuilder(schemaName).buildWhereClauseWithoutWhereKeyWord(queryArguments.flatMap(_.filter)) +
      WhereClauseBuilder(schemaName).buildCursorCondition(queryArguments, relatedModel).map(" AND " + _).getOrElse("") +
      OrderByClauseBuilder.internal("RelationTable", columnForRelatedModel, queryArguments) +
      LimitClauseBuilder.limitClause(queryArguments)
  }
}

case class ModelQueryBuilder(schemaName: String, model: Model, queryArguments: Option[QueryArguments]) extends QueryBuilder {
  val topLevelAlias = "Alias"

  lazy val queryString: String = {
    s"""SELECT * FROM "$schemaName"."${model.dbName}" AS "$topLevelAlias" """ +
      WhereClauseBuilder(schemaName).buildWhereClause(queryArguments.flatMap(_.filter)).getOrElse("") +
      WhereClauseBuilder(schemaName).buildCursorCondition(queryArguments, model).map(" AND " + _).getOrElse("") +
      OrderByClauseBuilder.forModel(model, topLevelAlias, queryArguments) +
      LimitClauseBuilder.limitClause(queryArguments)
  }

}

object SetParams {
  def setQueryArgs(preparedStatement: PreparedStatement, queryArguments: Option[QueryArguments]): Unit = {
    queryArguments.foreach { queryArgs =>
      setFilter(preparedStatement, queryArgs.filter)
    }
  }

  def setFilter(preparedStatement: PreparedStatement, filter: Option[Filter]): Unit = {
    filter.foreach { filter =>
      setParams(new PositionedParameters(preparedStatement), filter)
    }
  }

  def setParams(pp: PositionedParameters, filter: Filter): Unit = {
    filter match {
      //-------------------------------RECURSION------------------------------------
      case NodeSubscriptionFilter()                       => // NOOP
      case AndFilter(filters)                             => filters.foreach(setParams(pp, _))
      case OrFilter(filters)                              => filters.foreach(setParams(pp, _))
      case NotFilter(filters)                             => filters.foreach(setParams(pp, _))
      case NodeFilter(filters)                            => setParams(pp, OrFilter(filters))
      case RelationFilter(field, nestedFilter, condition) => setParams(pp, nestedFilter)
      //--------------------------------ANCHORS------------------------------------
      case PreComputedSubscriptionFilter(value)                     => // NOOP
      case ScalarFilter(field, Contains(value: StringGCValue))      => pp.setString("%" + value.value + "%")
      case ScalarFilter(field, NotContains(value: StringGCValue))   => pp.setString("%" + value.value + "%")
      case ScalarFilter(field, StartsWith(value: StringGCValue))    => pp.setString(value.value + "%")
      case ScalarFilter(field, NotStartsWith(value: StringGCValue)) => pp.setString(value.value + "%")
      case ScalarFilter(field, EndsWith(value: StringGCValue))      => pp.setString("%" + value.value)
      case ScalarFilter(field, NotEndsWith(value: StringGCValue))   => pp.setString("%" + value.value)
      case ScalarFilter(field, LessThan(value))                     => pp.setGcValue(value)
      case ScalarFilter(field, GreaterThan(value))                  => pp.setGcValue(value)
      case ScalarFilter(field, LessThanOrEquals(value))             => pp.setGcValue(value)
      case ScalarFilter(field, GreaterThanOrEquals(value))          => pp.setGcValue(value)
      case ScalarFilter(field, NotEquals(NullGCValue))              => // NOOP
      case ScalarFilter(field, NotEquals(value))                    => pp.setGcValue(value)
      case ScalarFilter(field, Equals(NullGCValue))                 => // NOOP
      case ScalarFilter(field, Equals(value))                       => pp.setGcValue(value)
      case ScalarFilter(field, In(Vector(NullGCValue)))             => // NOOP
      case ScalarFilter(field, NotIn(Vector(NullGCValue)))          => // NOOP
      case ScalarFilter(field, In(values))                          => values.foreach(pp.setGcValue)
      case ScalarFilter(field, NotIn(values))                       => values.foreach(pp.setGcValue)
      case OneRelationIsNullFilter(field)                           => // NOOP
      case x                                                        => sys.error(s"Not supported: $x")
    }
  }
}

case class WhereClauseBuilder(schemaName: String) {
  val topLevelAlias = "Alias"

  def buildWhereClause(filter: Option[Filter]): Option[String] = {
    val conditions = buildWhereClauseWithoutWhereKeyWord(filter)
    if (conditions.nonEmpty) {
      Some("WHERE " + conditions)
    } else {
      None
    }
  }

  def buildWhereClauseWithoutWhereKeyWord(filter: Option[Filter]) = {
    filter match {
      case Some(filter) => buildWheresForFilter(filter, topLevelAlias)
      case None         => "TRUE"
    }
  }

  // This creates a query that checks if the id is in a certain set returned by a subquery Q.
  // The subquery Q fetches all the ID's defined by the cursors and order.
  // On invalid cursor params, no error is thrown. The result set will just be empty.

  def buildCursorCondition(queryArguments: Option[QueryArguments], model: Model): Option[String] = {
    for {
      args   <- queryArguments
      result <- buildCursorCondition(args, model)
    } yield result
  }

  def buildCursorCondition(queryArguments: QueryArguments, model: Model): Option[String] = {
    val (before, after, orderBy) = (queryArguments.before, queryArguments.after, queryArguments.orderBy)
    // If both params are empty, don't generate any query.
    if (before.isEmpty && after.isEmpty) return None

    val tableName        = model.dbName
    val idFieldWithAlias = s""""$topLevelAlias"."${model.dbNameOfIdField_!}""""
    val idField          = s""""$schemaName"."$tableName"."${model.dbNameOfIdField_!}""""

    // First, we fetch the ordering for the query. If none is passed, we order by id, ascending.
    // We need that since before/after are dependent on the order.
    val (orderByField, orderByFieldWithAlias, sortDirection) = orderBy match {
      case Some(orderByArg) =>
        (s""""$schemaName"."$tableName"."${orderByArg.field.dbName}"""", s""""$topLevelAlias"."${orderByArg.field.dbName}"""", orderByArg.sortOrder.toString)
      case None => (idField, idFieldWithAlias, "asc")
    }

    // Then, we select the comparison operation and construct the cursors. For instance, if we use ascending order, and we want
    // to get the items before, we use the "<" comparator on the column that defines the order.
    def cursorFor(cursor: String, cursorType: String): String = {
      val compOperator = (cursorType, sortDirection.toLowerCase.trim) match {
        case ("before", "asc")  => "<"
        case ("before", "desc") => ">"
        case ("after", "asc")   => ">"
        case ("after", "desc")  => "<"
        case _                  => throw new IllegalArgumentException
      }

      s"""($orderByFieldWithAlias, $idFieldWithAlias) $compOperator ((select $orderByField from "$schemaName"."$tableName" where $idField = '$cursor'), '$cursor')"""
    }

    val afterCursorFilter  = after.map(cursorFor(_, "after"))
    val beforeCursorFilter = before.map(cursorFor(_, "before"))

    Some((afterCursorFilter ++ beforeCursorFilter).mkString(" AND "))
  }

  private def buildWheresForFilter(filter: Filter, alias: String): String = {
    filter match {
      //-------------------------------RECURSION------------------------------------
      case NodeSubscriptionFilter()                       => ""
      case AndFilter(filters)                             => filters.map(buildWheresForFilter(_, alias)).mkString(" AND ")
      case OrFilter(filters)                              => filters.map(buildWheresForFilter(_, alias)).mkString(" OR ")
      case NotFilter(filters)                             => "NOT " + filters.map(buildWheresForFilter(_, alias)).mkString(" AND NOT ")
      case NodeFilter(filters)                            => buildWheresForFilter(OrFilter(filters), alias)
      case RelationFilter(field, nestedFilter, condition) => relationFilterStatement(alias, field, nestedFilter, condition)
      //--------------------------------ANCHORS------------------------------------
      case PreComputedSubscriptionFilter(value)            => if (value) "TRUE" else "FALSE"
      case ScalarFilter(field, Contains(value))            => column(alias, field) + s""" LIKE ? """
      case ScalarFilter(field, NotContains(value))         => column(alias, field) + s""" NOT LIKE ? """
      case ScalarFilter(field, StartsWith(value))          => column(alias, field) + s""" LIKE ? """
      case ScalarFilter(field, NotStartsWith(value))       => column(alias, field) + s""" NOT LIKE ?"""
      case ScalarFilter(field, EndsWith(value))            => column(alias, field) + s""" LIKE ?"""
      case ScalarFilter(field, NotEndsWith(value))         => column(alias, field) + s""" NOT LIKE ?"""
      case ScalarFilter(field, LessThan(value))            => column(alias, field) ++ s""" < ?"""
      case ScalarFilter(field, GreaterThan(value))         => column(alias, field) ++ s""" > ?"""
      case ScalarFilter(field, LessThanOrEquals(value))    => column(alias, field) ++ s""" <= ?"""
      case ScalarFilter(field, GreaterThanOrEquals(value)) => column(alias, field) ++ s""" >= ?"""
      case ScalarFilter(field, NotEquals(NullGCValue))     => column(alias, field) ++ s""" IS NOT NULL"""
      case ScalarFilter(field, NotEquals(value))           => column(alias, field) ++ s""" != ?"""
      case ScalarFilter(field, Equals(NullGCValue))        => column(alias, field) + s""" IS NULL"""
      case ScalarFilter(field, Equals(value))              => column(alias, field) + s""" = ?"""
      case ScalarFilter(field, In(Vector(NullGCValue)))    => if (field.isRequired) s"false" else column(alias, field) ++ s""" IS NULL"""
      case ScalarFilter(field, NotIn(Vector(NullGCValue))) => if (field.isRequired) s"true" else column(alias, field) ++ s""" IS NOT NULL"""
      case ScalarFilter(field, In(values))                 => if (values.nonEmpty) column(alias, field) ++ in(values) else s"false"
      case ScalarFilter(field, NotIn(values))              => if (values.nonEmpty) column(alias, field) ++ s""" NOT """ ++ in(values) else s"true"
      case OneRelationIsNullFilter(field) =>
        val relation          = field.relation
        val relationTableName = relation.relationTableName
        val column            = relation.columnForRelationSide(field.relationSide)
        val otherIdColumn     = field.relatedModel_!.dbNameOfIdField_!

        s""" not exists (select  *
                from    "$schemaName"."$relationTableName"
                where   "$schemaName"."$relationTableName"."$column" = "$alias"."$otherIdColumn"
              )"""
      case x => sys.error(s"Not supported: $x")
    }
  }

  private def relationFilterStatement(alias: String, field: RelationField, nestedFilter: Filter, relationCondition: RelationCondition): String = {
    val relationTableName = field.relation.relationTableName
    val column            = field.relation.columnForRelationSide(field.relationSide)
    val oppositeColumn    = field.relation.columnForRelationSide(field.oppositeRelationSide)

    val newAlias = field.relatedModel_!.dbName + "_" + alias

    val join = s"""select *
            from "$schemaName"."${field.relatedModel_!.dbName}" as "$newAlias"
            inner join "$schemaName"."${relationTableName}"
            on "$newAlias"."${field.relatedModel_!.dbNameOfIdField_!}" = "$schemaName"."${relationTableName}"."${oppositeColumn}"
            where "$schemaName"."${relationTableName}"."${column}" = "$alias"."${field.model.dbNameOfIdField_!}" """

    val nestedFilterStatement = {
      val x = buildWheresForFilter(nestedFilter, newAlias)
      if (x.isEmpty) "TRUE" else x
    }

    relationCondition match {
      case AtLeastOneRelatedNode => s" exists (" + join + s"and " + nestedFilterStatement + ")"
      case EveryRelatedNode      => s" not exists (" + join + s"and not " + nestedFilterStatement + ")"
      case NoRelatedNode         => s" not exists (" + join + s"and " + nestedFilterStatement + ")"
      case NoRelationCondition   => s" exists (" + join + s"and " + nestedFilterStatement + ")"
    }
  }

  private def column(alias: String, field: Field): String = s""""$alias"."${field.dbName}" """
  private def in(items: Vector[GCValue])                  = s" IN (" + items.map(_ => "?").mkString(",") + ")"
}

object LimitClauseBuilder {

  def limitClause(args: Option[QueryArguments]): String = {
    val maxNodeCount                 = 1000
    val (firstOpt, lastOpt, skipOpt) = (args.flatMap(_.first), args.flatMap(_.last), args.flatMap(_.skip))

    (firstOpt, lastOpt, skipOpt) match {
      case (Some(first), _, _) if first < 0 => throw InvalidFirstArgument()
      case (_, Some(last), _) if last < 0   => throw InvalidLastArgument()
      case (_, _, Some(skip)) if skip < 0   => throw InvalidSkipArgument()
      case _ =>
        val count: Option[Int] = lastOpt.isDefined match {
          case true  => lastOpt
          case false => firstOpt
        }
        // Increase by 1 to know if we have a next page / previous page for relay queries
        val limitedCount = count match {
          case None                        => maxNodeCount
          case Some(x) if x > maxNodeCount => throw APIErrors.TooManyNodesRequested(x)
          case Some(x)                     => x + 1
        }
        s"LIMIT $limitedCount OFFSET ${skipOpt.getOrElse(0)}"
    }
  }
}

object OrderByClauseBuilder {

  def forModel(model: Model, alias: String, args: Option[QueryArguments]): String = {
    internal(
      alias = alias,
      secondaryOrderByField = model.dbNameOfIdField_!,
      args = args
    )
  }

  def forScalarListField(field: ScalarField, alias: String, args: Option[QueryArguments]): String = {
    val (first, last)  = (args.flatMap(_.first), args.flatMap(_.last))
    val isReverseOrder = last.isDefined

    if (first.isDefined && last.isDefined) throw APIErrors.InvalidConnectionArguments()

    // The limit instruction only works from up to down. Therefore, we have to invert order when we use before.
    val order = isReverseOrder match {
      case true  => "desc"
      case false => "asc"
    }

    //always order by nodeId, then positionfield ascending
    s""" ORDER BY "$alias"."nodeId" $order, "$alias"."position" $order """
  }

  def forRelation(relation: Relation, alias: String, args: Option[QueryArguments]): String = {
    internal(
      alias = alias,
      secondaryOrderByField = relation.columnForRelationSide(RelationSide.A),
      args = args
    )
  }

  def internal(alias: String, secondaryOrderByField: String, args: Option[QueryArguments]): String = {
    val (first, last, orderBy) = (args.flatMap(_.first), args.flatMap(_.last), args.flatMap(_.orderBy))
    val isReverseOrder         = last.isDefined

    if (first.isDefined && last.isDefined) throw APIErrors.InvalidConnectionArguments()

    // The limit instruction only works from up to down. Therefore, we have to invert order when we use before.
    val defaultOrder = orderBy.map(_.sortOrder.toString).getOrElse("asc")
    val (order, secondaryOrderByOrder) = isReverseOrder match {
      case true  => (invertOrder(defaultOrder), "desc")
      case false => (defaultOrder, "asc")
    }

    val aliasedSecondaryOrderByField = s""" "$alias"."$secondaryOrderByField" """

    orderBy match {
      case Some(orderByArg) if orderByArg.field.dbName != secondaryOrderByField =>
        val orderByField = s""" "$alias"."${orderByArg.field.dbName}" """

        // First order by the orderByField, then by the secondary order by field to break ties
        s""" ORDER BY $orderByField $order, $aliasedSecondaryOrderByField $secondaryOrderByOrder """

      case _ =>
        s""" ORDER BY $aliasedSecondaryOrderByField $order """
    }
  }

  private def invertOrder(order: String) = order.trim().toLowerCase match {
    case "desc" => "asc"
    case "asc"  => "desc"
    case _      => throw new IllegalArgumentException
  }
}
