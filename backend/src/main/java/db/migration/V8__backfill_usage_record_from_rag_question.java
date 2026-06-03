package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Locale;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V8__backfill_usage_record_from_rag_question extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        if (!tableExists(connection, "rag_question") || !tableExists(connection, "usage_record")) {
            return;
        }
        backfillQuestions(connection);
    }

    private void backfillQuestions(Connection connection) throws SQLException {
        boolean hasPromptTokens = columnExists(connection, "rag_question", "prompt_tokens");
        boolean hasCompletionTokens = columnExists(connection, "rag_question", "completion_tokens");
        boolean hasTotalTokens = columnExists(connection, "rag_question", "total_tokens");
        boolean hasCustomModel = columnExists(connection, "rag_question", "custom_model");

        String selectSql = """
            SELECT id, user_id, question_text, model_name, created_at%s%s%s%s
            FROM rag_question q
            WHERE NOT EXISTS (
                SELECT 1
                FROM usage_record ur
                WHERE ur.target_type = 'RAG_QUESTION'
                  AND ur.target_id = q.id
                  AND ur.action IN ('RAG_CHAT', 'RAG_CHAT_STREAM')
            )
            """.formatted(
            hasPromptTokens ? ", prompt_tokens" : "",
            hasCompletionTokens ? ", completion_tokens" : "",
            hasTotalTokens ? ", total_tokens" : "",
            hasCustomModel ? ", custom_model" : ""
        );

        try (
            Statement select = connection.createStatement();
            ResultSet rows = select.executeQuery(selectSql);
            PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO usage_record
                    (user_id, action, target_type, target_id, model_name, prompt_tokens, completion_tokens, total_tokens, detail, created_at)
                VALUES (?, 'RAG_CHAT_STREAM', 'RAG_QUESTION', ?, ?, ?, ?, ?, ?, ?)
                """)
        ) {
            while (rows.next()) {
                String model = rows.getString("model_name");
                Integer promptTokens = hasPromptTokens ? nullableInt(rows, "prompt_tokens") : null;
                Integer completionTokens = hasCompletionTokens ? nullableInt(rows, "completion_tokens") : null;
                Integer totalTokens = hasTotalTokens ? nullableInt(rows, "total_tokens") : null;
                boolean customModel = hasCustomModel && rows.getBoolean("custom_model");
                String detail = "model=" + safe(model)
                    + ", customModel=" + customModel
                    + ", promptTokens=" + zero(promptTokens)
                    + ", completionTokens=" + zero(completionTokens)
                    + ", totalTokens=" + zero(totalTokens)
                    + ", question=" + excerpt(rows.getString("question_text"));

                insert.setLong(1, rows.getLong("user_id"));
                insert.setLong(2, rows.getLong("id"));
                insert.setString(3, model);
                setNullableInt(insert, 4, promptTokens);
                setNullableInt(insert, 5, completionTokens);
                setNullableInt(insert, 6, totalTokens);
                insert.setString(7, detail);
                insert.setTimestamp(8, rows.getTimestamp("created_at"));
                insert.addBatch();
            }
            insert.executeBatch();
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

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName, columnName)) {
            if (columns.next()) {
                return true;
            }
        }
        try (ResultSet columns = connection.getMetaData().getColumns(null, null, tableName.toUpperCase(Locale.ROOT), columnName.toUpperCase(Locale.ROOT))) {
            return columns.next();
        }
    }

    private Integer nullableInt(ResultSet rows, String columnName) throws SQLException {
        int value = rows.getInt(columnName);
        return rows.wasNull() ? null : value;
    }

    private void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setInt(index, value);
        }
    }

    private int zero(Integer value) {
        return value == null ? 0 : value;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String excerpt(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replace("\r", " ").replace("\n", " ").trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
    }
}
