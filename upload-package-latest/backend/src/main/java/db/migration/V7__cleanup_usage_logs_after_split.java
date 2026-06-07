package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V7__cleanup_usage_logs_after_split extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!tableExists(connection, "system_log") || !tableExists(connection, "usage_record")) {
            return;
        }
        String actions = "'RAG_CHAT', 'RAG_CHAT_STREAM', 'UPLOAD_MATERIAL', 'CREATE_UPLOAD_SESSION'";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO usage_record (user_id, action, target_type, target_id, detail, created_at)
                SELECT actor_user_id, action, target_type, target_id, detail, created_at
                FROM system_log sl
                WHERE sl.action IN (%s)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM usage_record ur
                      WHERE ur.user_id = sl.actor_user_id
                        AND ur.action = sl.action
                        AND ((ur.target_id = sl.target_id) OR (ur.target_id IS NULL AND sl.target_id IS NULL))
                        AND ur.created_at = sl.created_at
                  )
                """.formatted(actions));
            statement.executeUpdate("DELETE FROM system_log WHERE action IN (" + actions + ")");
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(null, null, tableName, null)) {
            if (tables.next()) {
                return true;
            }
        }
        try (ResultSet tables = connection.getMetaData().getTables(null, null, tableName.toUpperCase(Locale.ROOT), null)) {
            return tables.next();
        }
    }
}
