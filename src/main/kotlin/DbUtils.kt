@file:Suppress("unused")

import java.sql.*

fun PreparedStatement.bindArgs(args: Array<out Any?>) = this.apply {
    args.forEachIndexed { iv, value ->
        val index = iv + 1
        when (value) {
            null -> setNull(index, Types.NULL)
            is String -> setString(index, value)
            is Boolean -> setInt(index, if (value) 1 else 0)
            is Long -> setLong(index, value)
            is Int -> setInt(index, value)
            else -> error("unsupported type ${value.javaClass.simpleName}")
        }
    }
}

fun ResultSet.readRow(meta: ResultSetMetaData = metaData) =
    HashMap<String, Any?>().apply {
        for (i in 1..meta.columnCount) {
            put(meta.getColumnName(i), getObject(i))
        }
    }

fun String.query1(conn: Connection, vararg args: Any?) =
    conn.prepareStatement(this).use { ps ->
        ps.bindArgs(args).executeQuery().use { rs ->
            if (rs.next()) rs.readRow() else null
        }
    }

fun String.queryList(conn: Connection, vararg args: Any?) =
    conn.prepareStatement(this).use { ps ->
        ps.bindArgs(args).executeQuery().use { rs ->
            ArrayList<HashMap<String, Any?>>().apply {
                while (rs.next()) {
                    add(rs.readRow())
                }
            }
        }
    }

fun String.execute(conn: Connection, vararg args: Any?) =
    conn.prepareStatement(this).use { it.bindArgs(args).execute() }

fun String.update(conn: Connection, vararg args: Any?) =
    conn.prepareStatement(this).use { it.bindArgs(args).executeUpdate() }
