-- Direction D: persist per-question review emphasis so spaced review can
-- distinguish weak oral dimensions (scored 0-1) from solid passes.
ALTER TABLE review_queue ADD COLUMN focus_dimensions TEXT;
