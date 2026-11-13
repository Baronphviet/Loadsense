-- Cho phép xóa TestConfig dù đã có lịch sử chạy (giữ nguyên lịch sử qua config_snapshot)
-- và bật ON DELETE CASCADE cho metrics/reports khi xóa TestExecution.
ALTER TABLE test_executions ALTER COLUMN test_config_id DROP NOT NULL;

DO $$
DECLARE
    c_name text;
BEGIN
    SELECT tc.constraint_name INTO c_name
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
      ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
    WHERE tc.table_name = 'test_executions' AND tc.constraint_type = 'FOREIGN KEY'
      AND kcu.column_name = 'test_config_id'
    LIMIT 1;
    IF c_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE test_executions DROP CONSTRAINT %I', c_name);
    END IF;
    ALTER TABLE test_executions
        ADD CONSTRAINT fk_test_executions_config
        FOREIGN KEY (test_config_id) REFERENCES test_configs(id) ON DELETE SET NULL;

    SELECT tc.constraint_name INTO c_name
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
      ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
    WHERE tc.table_name = 'test_metrics' AND tc.constraint_type = 'FOREIGN KEY'
      AND kcu.column_name = 'execution_id'
    LIMIT 1;
    IF c_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE test_metrics DROP CONSTRAINT %I', c_name);
    END IF;
    ALTER TABLE test_metrics
        ADD CONSTRAINT fk_test_metrics_execution
        FOREIGN KEY (execution_id) REFERENCES test_executions(id) ON DELETE CASCADE;

    SELECT tc.constraint_name INTO c_name
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
      ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
    WHERE tc.table_name = 'test_reports' AND tc.constraint_type = 'FOREIGN KEY'
      AND kcu.column_name = 'execution_id'
    LIMIT 1;
    IF c_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE test_reports DROP CONSTRAINT %I', c_name);
    END IF;
    ALTER TABLE test_reports
        ADD CONSTRAINT fk_test_reports_execution
        FOREIGN KEY (execution_id) REFERENCES test_executions(id) ON DELETE CASCADE;
END $$;
