package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V6__remove_user_llm_unique_index_and_move_usage_logs extends BaseJavaMigration {

    private static final String[] USAGE_ACTIONS = {
        "RAG_CHAT",
        "RAG_CHAT_STREAM",
        "UPLOAD_MATERIAL",
        "CREATE_UPLOAD_SESSION"
    };

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        removeUserLlmUniqueIndex(connection);
        moveUsageLogs(connection);
    }

    private void removeUserLlmUniqueIndex(Connection connection) throws SQLException {
        if (!tableExists(connection, "user_llm_config")) {
            return;
        }
        String database = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
        if (indexExists(connection, "user_llm_config", "uk_user_llm_config_user")) {
            executeIgnoringFailure(connection, database.contains("h2")
                ? "ALTER TABLE user_llm_config DROP CONSTRAINT uk_user_llm_config_user"
                : "DROP INDEX uk_user_llm_config_user ON user_llm_config");
            executeIgnoringFailure(connection, "ALTER TABLE user_llm_config DROP INDEX uk_user_llm_config_user");
        }
    }

    private void moveUsageLogs(Connection connection) throws SQLException {
        if (!tableExists(connection, "system_log") || !tableExists(connection, "usage_record")) {
            return;
        }
        String actions = quotedActions();
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

    private boolean indexExists(Connection connection, String tableName, String indexName) throws SQLException {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(null, null, tableName.toUpperCase(Locale.ROOT), false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void executeIgnoringFailure(Connection connection, String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ignored) {
            // Different databases expose UNIQUE constraints as either constraints or indexes.
        }
    }

    private String quotedActions() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < USAGE_ACTIONS.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append('\'').append(USAGE_ACTIONS[i]).append('\'');
        }
        return builder.toString();
    }
}
