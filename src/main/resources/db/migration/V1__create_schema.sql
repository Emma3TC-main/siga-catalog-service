-- Infrastructure provisions the schema. Do not require CREATE on the database.
-- This migration had never been applied successfully before this bootstrap.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_namespace
        WHERE nspname = 'catalog' AND pg_get_userbyid(nspowner) = current_user
    ) THEN
        RAISE EXCEPTION 'catalog must be provisioned with the migration login as owner';
    END IF;
END $$;
