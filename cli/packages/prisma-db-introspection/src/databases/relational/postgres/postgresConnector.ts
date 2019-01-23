import { RelationalConnector, ITable, IColumn, ITableRelation, IIndex } from '../relationalConnector'
import * as _ from 'lodash'
import { Client } from 'pg';
import { TypeIdentifier, DatabaseType } from 'prisma-datamodel';
import { PostgresIntrospectionResult } from './postgresIntrospectionResult'
import { RelationalIntrospectionResult } from '../relationalIntrospectionResult';

// Documentation: https://www.prisma.io/docs/data-model-and-migrations/introspection-mapping-to-existing-db-soi1/

// Responsible for extracting a normalized representation of a PostgreSQL database (schema)
export class PostgresConnector extends RelationalConnector {
  client: Client

  constructor(client: Client) {
    super()

    if (!(client instanceof Client)) {
      throw new Error('Postgres instance needed for initialization.')
    }

    this.client = client
  }

  public getDatabaseType(): DatabaseType {
    return DatabaseType.postgres
  }

  protected createIntrospectionResult(models: ITable[], relations: ITableRelation[]): RelationalIntrospectionResult {
    return new PostgresIntrospectionResult(models, relations)
  }

  protected async query(query: string, params?: any[]): Promise<any[]> {
    return (await this.client.query(query, params)).rows
  }


  public async listSchemas(): Promise<string[]> {
    const schemas = await super.listSchemas()
    return schemas.filter(schema => !schema.startsWith('pg_'))
  }

  protected async queryColumnComment(schemaName: string, tableName: string, columnName: string) {
    const commentQuery = `
    SELECT
    (
      SELECT
        pg_catalog.col_description(c.oid, cols.ordinal_position::int)
      FROM pg_catalog.pg_class c
      WHERE
        c.oid     = (SELECT cols.table_name::regclass::oid) AND
        c.relname = cols.table_name
    ) as column_comment   
    FROM
      information_schema.columns cols
    WHERE
      cols.table_schema  = $1::text AND
      cols.table_name    = $2::text AND
      cols.column_name   = $3::text;
    `
    const [comment] = (await this.client.query(commentQuery, [schemaName, tableName, columnName])).rows.map(row => row.column_comment as string)

    if(comment === undefined) {
      return null
    } else {
      return comment
    }
  }

  protected async queryIndices(schemaName: string, tableName: string) {
    const indexQuery = `
      SELECT
          tableInfos.relname as table_name,
          indexInfos.relname as index_name,
          array_agg(columnInfos.attname) as column_names,
          rawIndex.indisunique as is_unique
          rawIndex.indisprimary as is_primary_key
      FROM
          -- pg_class stores infos about tables, indices etc: https://www.postgresql.org/docs/9.3/catalog-pg-class.html
          pg_class tableInfos,
          pg_class indexInfos,
          -- pg_index stores indices: https://www.postgresql.org/docs/9.3/catalog-pg-index.html
          pg_index rawIndex,
          -- pg_attribute stores infos about columns: https://www.postgresql.org/docs/9.3/catalog-pg-attribute.html
          pg_attribute columnInfos,
          -- pg_namespace stores info about the schema
          pg_namespace schemaInfo
      WHERE
          -- find table info for index
          tableInfos.oid = rawIndex.indrelid
          -- find index info
          AND indexInfos.oid = rawIndex.indexrelid
          -- find table columns
          AND columnInfos.attrelid = tableInfos.oid
          AND columnInfos.attnum = ANY(rawIndex.indkey)
          -- we only consider oridnary tables
          AND tableInfos.relkind = 'r'
          -- we only consider stuff out of one specific schema
          AND tableInfos.relnamespace = schemaInfo.oid
          AND schemaInfo.nspname = $1::text
          AND tableInfos.relname = $2::text
      GROUP BY
          tableInfos.relname,
          indexInfos.relname
    `
    return (await this.client.query(indexQuery, [schemaName, tableName])).rows.map(row => { return {
      tableName: row.table_name as string,
      name: row.index_name as string,
      fields: row.column_names as string[],
      unique: row.is_unique as boolean,
      isPrimaryKey: row.is_primary_key as boolean
    }})
  }

  parseDefaultValue(string) {
    if (string == null) {
      return null
    }

    if (string.includes(`nextval('`)) {
      return '[AUTO INCREMENT]'
    }

    if (string.includes('now()') || string.includes("'now'::text")) {
      return null
    }

    if (string.includes('::')) {
      const candidate = string.split('::')[0]
      const withoutSuffix = candidate.endsWith(`'`)
        ? candidate.substring(0, candidate.length - 1)
        : candidate
      const withoutPrefix = withoutSuffix.startsWith(`'`)
        ? withoutSuffix.substring(1, withoutSuffix.length)
        : withoutSuffix

      if (withoutPrefix === 'NULL') {
        return null
      }

      return withoutPrefix
    }

    return string
  }

  toTypeIdentifier(
    type: string,
    field: string,
    isPrimaryKey: boolean,
  ): {
    typeIdentifier: TypeIdentifier | null
    comment: string | null
    error: string | null
  } {
    if (
      isPrimaryKey &&
      (type === 'character' ||
        type === 'character varying' ||
        type === 'text' ||
        type === 'uuid')
    ) {
      return {
        typeIdentifier: type === 'uuid' ? 'UUID' : 'ID',
        comment: null,
        error: null,
      }
    }

    if (type === 'uuid') {
      return { typeIdentifier: 'UUID', comment: null, error: null }
    }
    if (type === 'character') {
      return { typeIdentifier: 'String', comment: null, error: null }
    }
    if (type === 'character varying') {
      return { typeIdentifier: 'String', comment: null, error: null }
    }
    if (type === 'text') {
      return { typeIdentifier: 'String', comment: null, error: null }
    }
    if (type === 'smallint') {
      return { typeIdentifier: 'Int', comment: null, error: null }
    }
    if (type === 'integer') {
      return { typeIdentifier: 'Int', comment: null, error: null }
    }
    if (type === 'bigint') {
      return { typeIdentifier: 'Int', comment: null, error: null }
    }
    if (type === 'real') {
      return { typeIdentifier: 'Float', comment: null, error: null }
    }
    if (type === 'double precision') {
      return { typeIdentifier: 'Float', comment: null, error: null }
    }
    if (type === 'numeric') {
      return { typeIdentifier: 'Float', comment: null, error: null }
    }
    if (type === 'boolean') {
      return { typeIdentifier: 'Boolean', comment: null, error: null }
    }
    if (type === 'timestamp without time zone') {
      return { typeIdentifier: 'DateTime', comment: null, error: null }
    }
    if (type === 'timestamp with time zone') {
      return { typeIdentifier: 'DateTime', comment: null, error: null }
    }
    if (type === 'timestamp') {
      return { typeIdentifier: 'DateTime', comment: null, error: null }
    }
    if (type === 'json') {
      return { typeIdentifier: 'Json', comment: null, error: null }
    }
    if (type === 'date') {
      return { typeIdentifier: 'DateTime', comment: null, error: null }
    }

    return {
      typeIdentifier: null,
      comment: `Type '${type}' is not yet supported.`,
      error: `Not able to handle type '${type}'`,
    }
  }
}


const uniqueColumnsQuery = ` 
SELECT 
  tableConstraints.Constraint_Name,
  columnConstraint.Column_Name
FROM 
  information_schema.table_constraints tableConstraints
INNER JOIN 
  information_schema.constraint_column_usage columnConstraint ON tableConstraints.Constraint_Name = columnConstraint.Constraint_Name
WHERE
  tableConstraints.constraint_type = 'UNIQUE'
ORDER BY
  tableConstraints.Constraint_Name`