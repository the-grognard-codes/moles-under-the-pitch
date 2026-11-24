-- Apply to marker 10 while the service is stopped.
ALTER TABLE ffb_v2_match_members
  ADD COLUMN acknowledged_at_epoch_ms BIGINT UNSIGNED NULL,
  ADD INDEX ffb_v2_match_members_visible (account_id, acknowledged_at_epoch_ms, matchid);
UPDATE ffb_local_schema SET version=11 WHERE version=10;
