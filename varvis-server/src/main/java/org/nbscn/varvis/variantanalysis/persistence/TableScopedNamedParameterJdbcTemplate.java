package org.nbscn.varvis.variantanalysis.persistence;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.lang.NonNull;

import java.util.List;

/**
 * Rewrites only the known variant-analysis table identifiers. It leaves every
 * other SQL token untouched and is activated only with an explicit prefix.
 */
final class TableScopedNamedParameterJdbcTemplate extends NamedParameterJdbcTemplate {

    @NonNull
    private final NamedParameterJdbcTemplate delegate;
    @NonNull
    private final VariantAnalysisTableNames tables;

    TableScopedNamedParameterJdbcTemplate(@NonNull NamedParameterJdbcTemplate delegate,
                                          @NonNull VariantAnalysisTableNames tables) {
        // All invoked operations delegate below. The parent only needs a
        // non-null JdbcOperations instance for its constructor contract.
        super(new JdbcTemplate());
        this.delegate = delegate;
        this.tables = tables;
    }

    @Override
    public int update(@NonNull String sql, @NonNull SqlParameterSource paramSource) {
        return delegate.update(tables.rewrite(sql), paramSource);
    }

    @Override
    @NonNull
    public int[] batchUpdate(@NonNull String sql, @NonNull SqlParameterSource[] batchArgs) {
        return delegate.batchUpdate(tables.rewrite(sql), batchArgs);
    }

    @Override
    @NonNull
    public <T> List<T> query(@NonNull String sql,
                             @NonNull SqlParameterSource paramSource,
                             @NonNull RowMapper<T> rowMapper) {
        return delegate.query(tables.rewrite(sql), paramSource, rowMapper);
    }

    @Override
    public <T> T queryForObject(@NonNull String sql,
                                @NonNull SqlParameterSource paramSource,
                                @NonNull Class<T> requiredType) {
        return delegate.queryForObject(tables.rewrite(sql), paramSource, requiredType);
    }

    @Override
    public <T> T queryForObject(@NonNull String sql,
                                @NonNull SqlParameterSource paramSource,
                                @NonNull RowMapper<T> rowMapper) {
        return delegate.queryForObject(tables.rewrite(sql), paramSource, rowMapper);
    }

    @Override
    @NonNull
    public <T> List<T> queryForList(@NonNull String sql,
                                    @NonNull SqlParameterSource paramSource,
                                    @NonNull Class<T> elementType) {
        return delegate.queryForList(tables.rewrite(sql), paramSource, elementType);
    }
}
