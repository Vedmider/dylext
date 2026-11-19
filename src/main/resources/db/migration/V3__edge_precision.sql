-- I-003: overload/homonym-precise call-edge resolution.
-- Symbol arity (param count for methods/constructors) + call-site arity/receiver on edges.
ALTER TABLE symbols ADD COLUMN IF NOT EXISTS arity INT;
ALTER TABLE edges ADD COLUMN IF NOT EXISTS dst_arity INT;
ALTER TABLE edges ADD COLUMN IF NOT EXISTS dst_recv TEXT;
CREATE INDEX IF NOT EXISTS idx_symbols_repo_name ON symbols (repo, name);
