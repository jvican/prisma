package com.prisma.api.connector.sqlite.native

import com.prisma.api.connector.sqlite.SQLiteApiConnector
import com.prisma.api.connector.{ApiConnector, DataResolver, DatabaseMutactionExecutor}
import com.prisma.config.DatabaseConfig
import com.prisma.shared.models.{ConnectorCapabilities, Project, ProjectIdEncoder}

import scala.concurrent.{ExecutionContext, Future}

case class SQLiteApiConnectorNative(config: DatabaseConfig)(implicit ec: ExecutionContext) extends ApiConnector {
  lazy val base = SQLiteApiConnector(config, new org.sqlite.JDBC)

  override def initialize() = Future.unit
  override def shutdown()   = Future.unit

  override def databaseMutactionExecutor: DatabaseMutactionExecutor = {
    val exe = base.databaseMutactionExecutor
    new SQLiteDatabaseMutactionExecutor(exe.slickDatabase)
  }
  override def dataResolver(project: Project): DataResolver       = SQLiteNativeDataResolver(base.dataResolver(project))
  override def masterDataResolver(project: Project): DataResolver = SQLiteNativeDataResolver(base.dataResolver(project))
  override def projectIdEncoder: ProjectIdEncoder                 = ProjectIdEncoder('_')

  override val capabilities = ConnectorCapabilities.sqliteNative
}
