package org.nbscn.varvis.variantanalysis.persistence;

import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementTask;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

@Repository
public class JdbcVariantEnhancementRepository implements VariantEnhancementRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcVariantEnhancementRepository(
            @Qualifier("varvisNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
            VariantAnalysisProperties properties) {
        this.jdbcTemplate = new TableScopedNamedParameterJdbcTemplate(
                Objects.requireNonNull(jdbcTemplate, "jdbcTemplate"),
                new VariantAnalysisTableNames(properties.getPersistence().getTablePrefix())
        );
    }

    @Override
    public void save(VariantEnhancementTask task) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", task.getId())
                .addValue("analysisId", task.getAnalysisId())
                .addValue("provider", task.getProvider())
                .addValue("status", task.getStatus().name())
                .addValue("requestedCandidateLimit", task.getRequestedCandidateLimit())
                .addValue("selectedCandidateCount", task.getSelectedCandidateCount())
                .addValue("completedCount", task.getCompletedCount())
                .addValue("unavailableCount", task.getUnavailableCount())
                .addValue("errorMessage", task.getErrorMessage())
                .addValue("createdAt", Timestamp.from(task.getCreatedAt()))
                .addValue("updatedAt", Timestamp.from(task.getUpdatedAt()));
        jdbcTemplate.update(
                "INSERT INTO variant_analysis_enhancement ("
                        + "id, analysis_id, provider, status, requested_candidate_limit, "
                        + "selected_candidate_count, completed_count, unavailable_count, "
                        + "error_message, created_at, updated_at"
                        + ") VALUES ("
                        + ":id, :analysisId, :provider, :status, :requestedCandidateLimit, "
                        + ":selectedCandidateCount, :completedCount, :unavailableCount, "
                        + ":errorMessage, :createdAt, :updatedAt"
                        + ") ON DUPLICATE KEY UPDATE "
                        + "status = VALUES(status), "
                        + "selected_candidate_count = VALUES(selected_candidate_count), "
                        + "completed_count = VALUES(completed_count), "
                        + "unavailable_count = VALUES(unavailable_count), "
                        + "error_message = VALUES(error_message), "
                        + "updated_at = VALUES(updated_at)",
                parameters
        );
    }

    @Override
    public Optional<VariantEnhancementTask> findById(String id) {
        List<VariantEnhancementTask> tasks = jdbcTemplate.query(
                "SELECT id, analysis_id, provider, status, requested_candidate_limit, "
                        + "selected_candidate_count, completed_count, unavailable_count, "
                        + "error_message, created_at, updated_at "
                        + "FROM variant_analysis_enhancement WHERE id = :id",
                new MapSqlParameterSource("id", id),
                (resultSet, rowNumber) -> VariantEnhancementTask.restore(
                        resultSet.getString("id"),
                        resultSet.getString("analysis_id"),
                        resultSet.getString("provider"),
                        resultSet.getInt("requested_candidate_limit"),
                        VariantEnhancementStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("selected_candidate_count"),
                        resultSet.getInt("completed_count"),
                        resultSet.getInt("unavailable_count"),
                        resultSet.getString("error_message"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()
                )
        );
        return tasks.isEmpty() ? Optional.empty() : Optional.of(tasks.get(0));
    }

    @Override
    public int failInterruptedTasks() {
        return jdbcTemplate.update(
                "UPDATE variant_analysis_enhancement "
                        + "SET status = 'FAILED', "
                        + "error_message = 'Server restarted before enhancement completed', "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE status IN ('QUEUED', 'RUNNING')",
                new MapSqlParameterSource()
        );
    }
}
