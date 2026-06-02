package io.rhizome.example

import io.rhizome.sqlite.SqliteHandle
import io.rhizome.sqlite.SqliteRow
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * A JDBC (xerial sqlite-jdbc) binding of [SqliteHandle] for the demo client. Single connection,
 * single thread — exactly the single-DB-writer model the adapter assumes. ForestNote will instead
 * bind the adapter to a `SupportSQLiteDatabase` at cutover (P8); this is the JVM equivalent.
 */
class JdbcSqliteHandle(private val conn: Connection) : SqliteHandle {

    override fun execute(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.execute()
        }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqliteRow) -> T): List<T> {
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out.add(map(JdbcRow(rs)))
                return out
            }
        }
    }

    override fun <T> transaction(body: () -> T): T {
        val prev = conn.autoCommit
        conn.autoCommit = false
        try {
            val result = body()
            conn.commit()
            return result
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = prev
        }
    }

    private fun bind(st: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> st.setNull(idx, java.sql.Types.NULL)
                is ByteArray -> st.setBytes(idx, a)
                is Long -> st.setLong(idx, a)
                is Int -> st.setLong(idx, a.toLong())
                is Double -> st.setDouble(idx, a)
                is Boolean -> st.setLong(idx, if (a) 1L else 0L)
                is String -> st.setString(idx, a)
                else -> st.setObject(idx, a)
            }
        }
    }

    private class JdbcRow(private val rs: ResultSet) : SqliteRow {
        override fun getString(column: String): String? = rs.getString(column)
        override fun getLong(column: String): Long? = rs.getLong(column).let { if (rs.wasNull()) null else it }
        override fun getDouble(column: String): Double? = rs.getDouble(column).let { if (rs.wasNull()) null else it }
        override fun getBlob(column: String): ByteArray? = rs.getBytes(column)
    }

    companion object {
        /** Open (creating if absent) a file-backed SQLite database on its own connection. */
        fun open(path: String): JdbcSqliteHandle =
            JdbcSqliteHandle(DriverManager.getConnection("jdbc:sqlite:$path"))
    }
}
